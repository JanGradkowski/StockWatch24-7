package org.example.stockwatch247.service;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import tools.jackson.databind.ObjectMapper;

/** Read-only production candle snapshot; all checkpoint writes go to target/recovery-replay.
 * Compile to target/recovery-tools with javac and run with the application dependency classpath.
 * Arguments: SYMBOL 1d|1wk AS_OF_UTC [primary|all] [checkpoint-run] [continuous]. No provider requests or email sends.
 */
class AnalysisRecoveryReplay {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Snapshot(List<CandleBar> parents, List<CandleBar> children) { }
    record CandleBar(String symbol, String interval, long timestamp, double open, double high,
                     double low, double close, long volume) {
        Candle candle() { return new Candle(symbol, interval, timestamp, open, high, low, close, volume); }
    }
    static class FileStore extends AnalysisCheckpointStore {
        final Path directory;
        FileStore(Path directory) throws Exception { super(null, null); this.directory=directory; Files.createDirectories(directory); }
        @Override <T> T read(String key, Class<T> type) {
            try { Path path=directory.resolve(key+".json"); return Files.exists(path) ? JSON.readValue(Files.readString(path),type) : null; }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        @Override public void write(String key,Object value,long nodes) {
            try {
                Path temp=directory.resolve(key+".tmp"), target=directory.resolve(key+".json");
                Files.writeString(temp,JSON.writeValueAsString(value));
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }
    public static void main(String[] args) throws Exception {
        String symbol=args[0], interval=args[1]; Instant cutoff=Instant.parse(args[2]);
        Path root=Path.of("target","recovery-replay",symbol.replace('^','_')+"-v2-"+interval+"-"+cutoff.getEpochSecond());
        Files.createDirectories(root);
        Path snapshotPath=root.resolve("candles.json");
        Snapshot snapshot;
        if(Files.exists(snapshotPath)) snapshot=JSON.readValue(Files.readString(snapshotPath),Snapshot.class);
        else {
            var configuration=new Properties(); try(var reader=Files.newBufferedReader(Path.of(".env"))){configuration.load(reader);}
            try(var db=DriverManager.getConnection(configuration.getProperty("DB_URL","jdbc:postgresql://localhost:5432/uptodate_db"),
                    configuration.getProperty("DB_USERNAME","uptodate"),configuration.getProperty("DB_PASSWORD"))) {
                db.setReadOnly(true);
                snapshot=new Snapshot(load(db,symbol,interval,cutoff,1199),load(db,symbol,interval.equals("1wk")?"1d":"60min",cutoff,20000));
            }
            Files.writeString(snapshotPath,JSON.writeValueAsString(snapshot));
        }
        var parent=snapshot.parents().stream().map(CandleBar::candle).toList();
        var child=snapshot.children().stream().map(CandleBar::candle).toList();
        var frame=interval.equals("1wk")?TimeInterval.WEEKLY:TimeInterval.DAILY;
        var childFrame=interval.equals("1wk")?TimeInterval.DAILY:TimeInterval.ONE_HOUR;
        var enrich=new TechnicalIndicatorEnrichmentService();
        var parents=enrich.enrichForElliott(parent,1000,frame);
        var childWindow = child.stream().filter(c -> c.getTimestamp() >= parents.getFirst().timestamp()).toList();
        var children = childWindow.isEmpty() || parents.size() < 2
                || childWindow.getFirst().getTimestamp() >= parents.get(1).timestamp() ? List.<EnrichedCandle>of()
                : enrich.enrichForElliott(childWindow,childWindow.size(),childFrame);
        String run=args.length>4 ? args[4] : "";
        if(!run.matches("[a-zA-Z0-9_-]*")) throw new IllegalArgumentException("Invalid checkpoint run name");
        String suffix=run.isEmpty()?"":"-"+run;
        var store=new FileStore(root.resolve("checkpoints"+suffix));
        long started=System.nanoTime(); int attempts=0;
        long cpuStarted=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis();
        System.out.println("Snapshot symbol="+symbol+" interval="+interval+" parents="+parents.size()+" children="+children.size()+" cutoff="+cutoff);
        while(true) {
            attempts++; long sliceStart=System.nanoTime();
            long sliceCpuStart=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis();
            try(var work=args.length>5 && args[5].equals("continuous")
                    ? AnalysisWork.continuous(store,Duration.ofSeconds(30)) : AnalysisWork.open(store,Duration.ofSeconds(30));
                    var scope=AnalysisComputationScope.open()) {
                boolean reference = args.length > 6 && args[6].equals("reference");
                ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ElliottWaveDetectionService.class))
                        .setLevel(ch.qos.logback.classic.Level.DEBUG);
                var detector=new ElliottWaveDetectionService() {
                    @Override boolean reuseCorrectionSuffixes() { return !reference; }
                };
                var result=detector.findDevelopingImpulses(parents,children);
                Files.writeString(root.resolve("primary"+suffix+".json"),JSON.writeValueAsString(result));
                System.out.println("Primary completed count="+result.size()+" slices="+attempts+" totalMs="+(System.nanoTime()-started)/1_000_000);
                if(args.length>3 && args[3].equals("all")) {
                    long stage=System.nanoTime();
                    var previous=detector.findDevelopingImpulses(parents.subList(0,parents.size()-1),
                            children.stream().filter(c->c.timestamp()<parents.getLast().timestamp()).toList());
                    System.out.println("Previous-prefix count="+previous.size()+" ms="+(System.nanoTime()-stage)/1_000_000);
                    stage=System.nanoTime();
                    var observations=detector.newConfluenceObservations(parents,parents.subList(0,parents.size()-1),
                            new ElliottWaveDetectionService.DevelopingScanContext());
                    Files.writeString(root.resolve("observations"+suffix+".json"),JSON.writeValueAsString(observations));
                    System.out.println("Elliott confluence count="+observations.size()+" ms="+(System.nanoTime()-stage)/1_000_000);
                    stage=System.nanoTime();
                    var harmonics=new HarmonicPatternDetectionService(); var formations=harmonics.detectNewlyAvailable(parent);
                    System.out.println("Harmonics count="+formations.size()+" ms="+(System.nanoTime()-stage)/1_000_000);
                    var confluence=new CrossPatternConfluenceService(new CandlePatternDetectionService());
                    var timeline=confluence.buildTimeline(parent,enrich.enrich(parent,100,frame),parents,frame,null,null,detector,harmonics);
                    for(var formation:formations) {
                        stage=System.nanoTime(); confluence.assess(50,AlertPatternFamily.HARMONIC_FORMATION,formation.tradeSignal(),formation.confirmationTimestamp(),timeline);
                        System.out.println("Confluence pattern="+formation.pattern()+" ms="+(System.nanoTime()-stage)/1_000_000);
                    }
                    stage=System.nanoTime();
                    enrich.analyze(parent.subList(Math.max(0,parent.size()-519),parent.size()),320,TechnicalIndicatorProfile.forInterval(frame),true);
                    System.out.println("Outlook indicator analysis ms="+(System.nanoTime()-stage)/1_000_000);
                }
                long cpuMs=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis()-cpuStarted;
                System.out.println("Replay finished totalMs="+(System.nanoTime()-started)/1_000_000+" cpuMs="+cpuMs); break;
            } catch(AnalysisDeferredException deferred) {
                long cpuNow=ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis();
                System.out.println("Checkpoint saved slice="+attempts+" sliceMs="+(System.nanoTime()-sliceStart)/1_000_000
                        +" sliceCpuMs="+(cpuNow-sliceCpuStart)+" totalCpuMs="+(cpuNow-cpuStarted));
            }
        }
    }
    private static List<CandleBar> load(Connection db,String symbol,String interval,Instant cutoff,int limit)throws Exception {
        var bars=new ArrayList<CandleBar>();
        try(var query=db.prepareStatement("select timestamp,open_price,high_price,low_price,close_price,volume from candles where symbol=? and time_interval=? and timestamp < ? order by timestamp desc limit ?")) {
            query.setString(1,symbol);query.setString(2,interval);query.setLong(3,cutoff.getEpochSecond());query.setInt(4,limit);
            try(var rows=query.executeQuery()){while(rows.next())bars.add(new CandleBar(symbol,interval,rows.getLong(1),rows.getDouble(2),rows.getDouble(3),rows.getDouble(4),rows.getDouble(5),rows.getLong(6)));}
        }
        Collections.reverse(bars);return List.copyOf(bars);
    }
}


import java.util.*;
import java.nio.file.*;
import java.time.*;
import java.lang.reflect.*;
import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.service.*;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.*;

public class PatternAuditProbe {
  static HarmonicPatternDetectionService h = new HarmonicPatternDetectionService();
  static ElliottWaveDetectionService e = new ElliottWaveDetectionService();
  static long ts(int i) { return 1700000000L+i*86400L; }
  static List<HarmonicPivot> hp(double... p) {
    var result = new ArrayList<HarmonicPivot>();
    for(int i=0;i<p.length;i++) result.add(new HarmonicPivot(i*5,ts(i*5),p[i],i%2==0?PivotType.LOW:PivotType.HIGH,ts(i*5+2)));
    return result;
  }
  static List<EnrichedCandle> series(double... p) {
    var result = new ArrayList<EnrichedCandle>();
    for(int k=0;k<p.length-1;k++) for(int j=0;j<8;j++) result.add(ec(result.size(),p[k]+(p[k+1]-p[k])*j/8));
    result.add(ec(result.size(),p[p.length-1]));
    return result;
  }
  static EnrichedCandle ec(int i,double p) { return new EnrichedCandle(ts(i),p,p,p,p,1000,1000,50,p,p,p-10,p+10,1); }
  static void harmonic(String label,double... p) { System.out.println(label+" -> "+h.classify(hp(p)).map(x->x.pattern().name()).orElse("MISSED")); }
  static Object pivot(int i,double p,boolean high) throws Exception {
    Class<?> type=Class.forName("org.example.stockwatch247.service.ElliottWaveDetectionService$PivotType");
    Class<?> pc=Class.forName("org.example.stockwatch247.service.ElliottWaveDetectionService$Pivot");
    var ctor=pc.getDeclaredConstructors()[0];ctor.setAccessible(true);
    Object pt=Arrays.stream(type.getEnumConstants()).filter(x->x.toString().equals(high?"HIGH":"LOW")).findFirst().orElseThrow();
    return ctor.newInstance(i,pt,p);
  }
  static boolean privateSequence(String name,double... prices)throws Exception {
    var ps=new ArrayList<Object>();for(int i=0;i<prices.length;i++)ps.add(pivot(i*8,prices[i],i%2==1));
    var m=ElliottWaveDetectionService.class.getDeclaredMethod(name,List.class);m.setAccessible(true);
    return (boolean)m.invoke(e,ps);
  }
  public static void main(String[] args)throws Exception {
    harmonic("Control Gartley",100,200,138.2,183.2,121.4);
    harmonic("Bat B=.55, CD/AB=1.27",100,200,145,181.25,111.4);
    harmonic("Bat CD/AB=1.618",100,200,150,192.3,111.4);
    harmonic("Butterfly BC=2.618, CD/AB~1",100,200,121.4,121.4+48.4/1.618,73);
    harmonic("Shark completion=1.0",100,200,144,214,100);
    System.out.println("Strict diagonal accepts non-wedge 100,120,110,150,115,155 -> "+e.findStrictSubdivisions(series(100,120,110,150,115,155),"V",100,155).stream().map(x->x.structureLabel()+" validated="+x.validated()).toList());
    System.out.println("Strict zigzag with C failing A, 200,160,180,170 -> "+e.findStrictSubdivisions(series(200,160,180,170),"II",200,170).stream().map(x->x.structureLabel()+" validated="+x.validated()).toList());
    System.out.println("Strict parent boundaries 1..1000 absent from child prices 100..160 -> "+e.findStrictSubdivisions(series(100,120,110,150,130,160),"I",1,1000).stream().map(x->x.structureLabel()+" validated="+x.validated()+" actual="+x.points().getFirst().price()+".."+x.points().getLast().price()).toList());
    System.out.println("Top-level bullish correction accepts C above V: "+privateSequence("isBullishCorrectionComplete",100,120,110,150,130,160,140,178,170));
    System.out.println("Top-level bullish correction accepts C below origin: "+privateSequence("isBullishCorrectionComplete",100,120,110,150,130,160,140,150,90));
    System.out.println("Public historical detector with wrong-side C -> "+e.findHistoricalWaveStructures(series(100,120,110,150,130,160,140,178,170,179)).stream().filter(x->x.correctionComplete()).map(x->x.direction()+" "+x.correctionVariant()+" V="+x.points().get(5).price()+" C="+x.points().get(8).price()).toList());
    System.out.println("Public raw signal with wrong-side C -> "+e.detect(series(100,120,110,150,130,160,140,178,170,179).subList(0,66)).stream().map(x->x.pattern()+" "+x.confidenceScore()).toList());
    var batches=new LinkedHashMap<String,List<Candle>>();
    for(String line:Files.readAllLines(Path.of("src/test/resources/elliott-wave/historical-benchmarks.csv"))) {
      if(line.startsWith("#")||line.startsWith("case")||line.isBlank())continue;
      String[] f=line.split(",");
      if(!f[3].matches("\\d{4}-\\d{2}-\\d{2}"))continue;
      long t=LocalDate.parse(f[3]).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
      batches.computeIfAbsent(f[0],x->new ArrayList<>()).add(new Candle(f[1],"1wk",t,Double.parseDouble(f[4]),Double.parseDouble(f[5]),Double.parseDouble(f[6]),Double.parseDouble(f[7]),Long.parseLong(f[8])));
    }
    var svc=new CrossPatternConfluenceService(new CandlePatternDetectionService());
    var enrichment=new TechnicalIndicatorEnrichmentService();
    var emptyH=new HarmonicPatternDetectionService(){public List<HarmonicFormation> detectAll(List<Candle> c){return List.of();}};
    for(var batch:batches.entrySet()) {
      var raw=batch.getValue();var enriched=enrichment.enrichForElliott(raw,raw.size());
      var full=svc.buildTimeline(raw,List.of(),enriched,TimeInterval.WEEKLY,null,null,e,emptyH);
      if(batch.getKey().equals("stockcharts_spx_2004_impulse"))System.out.println("Bullish wave V observation -> "+full.observations());
      int differs=0;String first="";
      for(int n=34;n<raw.size();n++) {
        var prefixRaw=raw.subList(0,n);var pe=enrichment.enrichForElliott(prefixRaw,n);
        var prefix=svc.buildTimeline(prefixRaw,List.of(),pe,TimeInterval.WEEKLY,null,null,e,emptyH);
        long t=prefixRaw.getLast().getTimestamp();
        var a=svc.assess(50,AlertPatternFamily.HARMONIC_FORMATION,TradeSignal.BUY,t,full);
        var b=svc.assess(50,AlertPatternFamily.HARMONIC_FORMATION,TradeSignal.BUY,t,prefix);
        if(a.adjustedScore()!=b.adjustedScore()) {differs++;if(first.isEmpty())first=Instant.ofEpochSecond(t)+" full="+a.adjustedScore()+" prefix="+b.adjustedScore();}
      }
      System.out.println("Confluence prefix comparison "+batch.getKey()+": changed scores="+differs+" first="+first);
    }
  }
}

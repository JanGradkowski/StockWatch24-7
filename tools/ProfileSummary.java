import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;
class ProfileSummary {
    public static void main(String[] args) throws Exception {
        Map<String,Integer> top=new HashMap<>(), app=new HashMap<>();
        try(var recording=new RecordingFile(Path.of(args[0]))) {
            while(recording.hasMoreEvents()) {
                var event=recording.readEvent();
                if(!event.getEventType().getName().equals("jdk.ExecutionSample") || event.getStackTrace()==null) continue;
                var frames=event.getStackTrace().getFrames();
                if(!frames.isEmpty()) top.merge(name(frames.getFirst()),1,Integer::sum);
                frames.stream().filter(f->f.getMethod().getType().getName().startsWith("org.example"))
                        .findFirst().ifPresent(f->app.merge(name(f),1,Integer::sum));
            }
        }
        for(var map:List.of(top,app)) {
            map.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(20).forEach(System.out::println);
            System.out.println();
        }
    }
    static String name(RecordedFrame f) {return f.getMethod().getType().getName()+"."+f.getMethod().getName()+":"+f.getLineNumber();}
}

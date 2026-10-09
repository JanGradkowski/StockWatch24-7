import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import tools.jackson.databind.ObjectMapper;

/** One provider request to verify the canonical Home Depot listing; never logs its API key. */
class ProviderIdentityProbe {
    public static void main(String[] args) throws Exception {
        var p=new Properties();try(var r=Files.newBufferedReader(Path.of(".env"))){p.load(r);}
        String key=p.getProperty("TWELVE_DATA_API_KEY","");
        if(key.isBlank()) throw new IllegalStateException("Twelve Data is not configured.");
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()) {
            var uri=URI.create("https://api.twelvedata.com/time_series?symbol=HD&mic_code=XNYS&interval=1day&outputsize=1&apikey="+URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8));
            try {
                var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
                var body=new ObjectMapper().readTree(response.body());
                System.out.println("HD requested MIC=XNYS HTTP="+response.statusCode()+" providerCode="+body.path("code").asInt(0)
                    +" returnedMIC="+body.path("meta").path("mic_code").asText("")+" bars="+body.path("values").size());
            } catch(Exception failure) {System.out.println("HD identity probe failed: "+failure.getClass().getSimpleName());}
        }
    }
}

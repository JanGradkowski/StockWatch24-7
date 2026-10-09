import java.nio.file.*;
import java.util.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Connects and authenticates only. Never sends a message or prints credentials. */
class SmtpConnectivityProbe {
    public static void main(String[] args) throws Exception {
        var p=new Properties(); try(var reader=Files.newBufferedReader(Path.of(".env"))) {p.load(reader);}
        int port=args.length == 0 ? Integer.parseInt(p.getProperty("SMTP_PORT","587")) : Integer.parseInt(args[0]);
        boolean ssl=port == 465;
        var sender=new JavaMailSenderImpl(); sender.setHost(p.getProperty("SMTP_HOST"));sender.setPort(port);
        sender.setUsername(p.getProperty("SMTP_USERNAME"));sender.setPassword(p.getProperty("SMTP_PASSWORD"));
        var props=sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth","true");props.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));
        props.setProperty("mail.smtp.ssl.checkserveridentity","true");
        props.setProperty("mail.smtp.localhost",p.getProperty("SMTP_EHLO_HOST","stockwatch.local"));
        props.setProperty("mail.smtp.starttls.enable",Boolean.toString(!ssl));
        props.setProperty("mail.smtp.starttls.required",Boolean.toString(!ssl));
        props.setProperty("mail.smtp.connectiontimeout","8000");props.setProperty("mail.smtp.timeout","8000");
        try { sender.testConnection();System.out.println("SMTP authentication succeeded port="+port+" implicitTLS="+ssl+"; no message sent"); }
        catch(Exception failure) {
            System.out.println("SMTP authentication failed port="+port+" failureType="+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}

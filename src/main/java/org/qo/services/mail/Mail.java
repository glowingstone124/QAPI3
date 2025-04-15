package org.qo.services.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.qo.utils.Logger;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Mail {
    public static final String MAIL_CONFIG = "data/mail.json";
    public String sender, host, password;

    public Mail() {
        try {
            String mailConfigContent = Files.readString(Path.of(MAIL_CONFIG));
            JsonObject mailObj = JsonParser.parseString(mailConfigContent).getAsJsonObject();
            this.sender = mailObj.get("sender").getAsString();
            this.host = mailObj.get("host").getAsString();
            this.password = mailObj.get("password").getAsString();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /*** Send E-MAIL to target email addr.
     * @param reciver Mail Reciver
     * @param subject Mail Subject
     * @param htmlContent Mail Content
     */

    public void send(@NotNull final String reciver, @NotNull final String subject, @NotNull final String htmlContent) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp-mail.outlook.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props,
                new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(host, password);
                    }
                });
        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sender));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(reciver));
            message.setSubject(subject);
            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }
    public boolean test(){
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp-mail.outlook.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props,
                new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(host, password);
                    }
                });
        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sender));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("hanserofficial@outlook.com"));
            message.setSubject("QAPI test");
            message.setContent("<p>TEST CONTENT</p>", "text/html; charset=utf-8");
            Transport.send(message);
        } catch (MessagingException e) {
            Logger.log(e.getMessage(), Logger.LogLevel.ERROR);
            return false;
        }
        return true;
    }
}

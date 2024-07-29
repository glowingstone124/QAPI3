package org.qo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

public class Algorithm {
    public static String token(String player_name, long qq) {
        String charset = "qazxswedcvfrtgbnhyujmkiolp0129384756_POILKJMNBUYTHGFVCXREWQDSAZ";
        ArrayList<Integer> remix = new ArrayList<>();

        if(qq<=0) throw new IllegalArgumentException("Invalid QQ ID");
        long qq_copy = qq;
        for (qq = qq + 707; qq!=0; qq/=64) {
            remix.add((int) (qq%64));
        }
        for (char c:player_name.toCharArray()) {
            if(charset.indexOf(c)==-1) throw new IllegalArgumentException("Invalid player name character '"+c+"' in "+player_name);
            remix.add(charset.indexOf(c));
        }
        if(remix.size()%2==1) remix.add((int) (qq_copy%32) * 2);
        String result = "";
        double node = 707;
        int size = remix.size()/2;
        for(int i = 0; i < 16; i++) {
            double value = 0;
            for(int j = 0; j < size; j++) {
                value += Math.sin(remix.get(j * 2) * node + remix.get(j * 2 + 1));
            }
            node += value * 707;
            result = result + Integer.toHexString(sigmoid(value));
        }
        return result;
    }

    public static int sigmoid(double value) {
        double sigmoid_result = 1d/(1d+Math.exp(0-value));
        int result = (int) Math.floor(sigmoid_result * 256);
        if(result >= 256) return 255;
        else return Math.max(result, 0);
    }
    public static String hashSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte hashByte : hashBytes) {
                String hex = Integer.toHexString(0xff & hashByte);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
}

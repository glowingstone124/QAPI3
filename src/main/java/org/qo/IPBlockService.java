package org.qo;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

public class IPBlockService {

    private Set<String> blockedIPs = new HashSet<>();
    private String filePath;

    public IPBlockService(String filePath) {
        this.filePath = filePath;
        loadBlockedIPs();
    }
    private void loadBlockedIPs() {
        File file = new File(filePath);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    blockedIPs.add(line.trim());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Blocked IPs file not found: " + filePath);
        }
    }
    public void addBlockedIP(String ip) {
        blockedIPs.add(ip);
        saveBlockedIPs();
    }
    public void removeBlockedIP(String ip) {
        blockedIPs.remove(ip);
        saveBlockedIPs();
    }
    public boolean isBlocked(String ip) {
        return blockedIPs.contains(ip);
    }
    private void saveBlockedIPs() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
            for (String ip : blockedIPs) {
                writer.write(ip);
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public Set<String> getBlockedIPs() {
        return new HashSet<>(blockedIPs);
    }
}

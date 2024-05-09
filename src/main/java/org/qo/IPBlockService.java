package org.qo;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IPBlockService {

    private Set<String> blockedIPs = new HashSet<>();
    private final String filePath;
    private final ScheduledExecutorService scheduler;

    public IPBlockService(String filePath) {
        this.filePath = filePath;
        this.scheduler = Executors.newScheduledThreadPool(1);
        loadBlockedIPs();
        schedulePeriodicReload();
    }

    private void loadBlockedIPs() {
        File file = new File(filePath);
        Set<String> newBlockedIPs = new HashSet<>();
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    newBlockedIPs.add(line.trim());
                }
                blockedIPs = newBlockedIPs;
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Blocked IPs file not found: " + filePath);
        }
    }

    private void schedulePeriodicReload() {
        scheduler.scheduleAtFixedRate(this::loadBlockedIPs, 120, 120, TimeUnit.SECONDS);
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
    public void shutdown() {
        scheduler.shutdown();
    }
}

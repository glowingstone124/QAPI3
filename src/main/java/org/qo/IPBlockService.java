package org.qo;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IPBlockService {
    private static IPBlockService instance;
    private Set<String> blockedIPs = new HashSet<>();
    private final String filePath;
    private final ScheduledExecutorService scheduler;

    private IPBlockService(String filePath) {
        this.filePath = filePath;
        this.scheduler = Executors.newScheduledThreadPool(1);
        loadBlockedIPs();
        schedulePeriodicReload();
    }

    public static synchronized IPBlockService getInstance(String filePath) {
        if (instance == null) {
            instance = new IPBlockService(filePath);
        }
        return instance;
    }

    public void addBlockedIP(String ip) {
        synchronized (blockedIPs) {
            if (!ip.startsWith("192.168") || !ip.startsWith("127.0") || !ip.startsWith("10.64")) {
                blockedIPs.add(ip);
                saveBlockedIPs();
            }
        }
    }

    public void removeBlockedIP(String ip) {
        synchronized (blockedIPs) {
            blockedIPs.remove(ip);
            saveBlockedIPs();
        }
    }

    public boolean isBlocked(String ip) {
        synchronized (blockedIPs) {
            return blockedIPs.contains(ip);
        }
    }

    private void loadBlockedIPs() {
        synchronized (blockedIPs) {
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
    }

    private void saveBlockedIPs() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
            synchronized (blockedIPs) {
                for (String ip : blockedIPs) {
                    writer.write(ip);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void schedulePeriodicReload() {
        scheduler.scheduleAtFixedRate(this::loadBlockedIPs, 120, 120, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}

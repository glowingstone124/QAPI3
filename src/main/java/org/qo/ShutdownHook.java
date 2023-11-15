package org.qo;

public class ShutdownHook extends Thread {

    @Override
    public void run() {
        System.out.println("EXITED.");
        System.exit(0);
    }
}

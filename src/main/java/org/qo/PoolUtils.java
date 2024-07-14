package org.qo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PoolUtils {
    public ExecutorService es = Executors.newCachedThreadPool();
    public void submit(Runnable task) {
        es.submit(task);
    }
}

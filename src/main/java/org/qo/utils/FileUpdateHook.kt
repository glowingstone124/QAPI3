package org.qo.utils

import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.absolute

@Service
class FileUpdateHook {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchHookMaps: ConcurrentHashMap<Path, WatchKey> = ConcurrentHashMap()
    private val fileHooks: ConcurrentHashMap<Path, () -> Unit> = ConcurrentHashMap()
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun addHook(file: File, function: () -> Unit, vararg eventKinds: WatchEvent.Kind<*>) {
        val path = file.toPath().absolute().parent
        val fileName = file.name
        val watchKey = path.register(watchService, *eventKinds)
        watchHookMaps[path] = watchKey
        fileHooks[file.toPath()] = function

        executor.submit {
            while (true) {
                val watchKey = watchService.take()

                for (event in watchKey.pollEvents()) {
                    val eventPath = event.context() as Path
                    if (eventPath.toString() == fileName) {
                        fileHooks[file.toPath()]?.invoke()
                    }
                }

                val valid = watchKey.reset()
                if (!valid) {
                    break
                }
            }
        }
    }

    fun removeHook(file: File) {
        val path = file.toPath().parent
        val watchKey = watchHookMaps[path]
        watchKey?.cancel()
        watchHookMaps.remove(path)
        fileHooks.remove(file.toPath())
    }

    fun stop() {
        watchService.close()
        executor.shutdown()
    }
}

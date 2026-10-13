package com.qing.hachimi.desktop;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileLockHolder {
    private FileLockHolder() {}

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            Thread.sleep(60_000);
        }
    }
}

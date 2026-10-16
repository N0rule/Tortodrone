package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;

/** Explicit, offline schema-8 to current-schema candidate export. Never updates the original database. */
public final class TownUpgrade {
    private TownUpgrade() { }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: TownUpgrade source-data-directory fresh-candidate-directory");
        Path source=Path.of(args[0]).toAbsolutePath().normalize(),output=Path.of(args[1]).toAbsolutePath().normalize();
        if(Files.exists(output)||!Files.isRegularFile(source.resolve("tortodrone.db")))throw new IOException("Source database required; candidate directory must be new");
        Class.forName("org.sqlite.JDBC");
        try(var channel=FileChannel.open(source.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Stop the server before exporting an upgrade candidate");
            try(var connection=DriverManager.getConnection("jdbc:sqlite:file:"+source.resolve("tortodrone.db")+"?mode=ro");var s=connection.createStatement()){
                try(var r=s.executeQuery("PRAGMA user_version")){r.next();if(r.getInt(1)!=8)throw new IOException("Only schema 8 can be exported by this tool");}
                Files.createDirectory(output);
                try(var vacuum=connection.prepareStatement("VACUUM INTO ?")){vacuum.setString(1,output.resolve("tortodrone.db").toString());vacuum.execute();}
            }
            if(Files.getFileStore(output).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(output.resolve("tortodrone.db"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+output.resolve("tortodrone.db"));var s=c.createStatement()){
                s.execute("PRAGMA foreign_keys=ON");s.execute("BEGIN IMMEDIATE");try{TownStore.initialize(c);s.execute("PRAGMA user_version="+Database.SCHEMA_VERSION);s.execute("COMMIT");}catch(Exception failure){s.execute("ROLLBACK");throw failure;}
            }
        }
        System.out.println("Exported schema-"+Database.SCHEMA_VERSION+" candidate; original database unchanged: "+output.resolve("tortodrone.db"));
    }
}

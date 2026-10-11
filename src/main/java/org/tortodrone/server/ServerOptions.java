package org.tortodrone.server;

import java.io.IOException;
import java.nio.file.Path;

/** Pure argument parsing; --nogui never queries or initializes a desktop toolkit. */
public record ServerOptions(Path config, Mode mode) {
    public enum Mode { AUTO, GUI, TERMINAL }
    public static ServerOptions parse(String[] args,Path defaultConfig) throws IOException {
        Path config=defaultConfig;Mode mode=Mode.AUTO;boolean configured=false;
        for(int n=0;n<args.length;n++)switch(args[n]) {
            case "--gui", "--nogui" -> {
                if(mode!=Mode.AUTO)throw new IOException("Choose only one of --gui and --nogui");
                mode=args[n].equals("--gui")?Mode.GUI:Mode.TERMINAL;
            }
            case "--config" -> {if(configured||++n>=args.length)throw new IOException("--config requires one path");config=Path.of(args[n]);configured=true;}
            default -> throw new IOException("Use --help for server options; type help in the running server terminal");
        }
        return new ServerOptions(config,mode);
    }
    public boolean desktopRequested() throws IOException {
        if(mode==Mode.TERMINAL)return false;
        boolean available=!java.awt.GraphicsEnvironment.isHeadless();
        if(!available&&mode==Mode.GUI)throw new IOException("--gui requires an available graphical desktop; use --nogui on headless systems");
        return available;
    }
}

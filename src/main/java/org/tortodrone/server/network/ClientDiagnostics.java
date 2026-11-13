package org.tortodrone.server.network;

import java.net.Socket;
import java.util.List;
import org.tortodrone.account.AccountStore;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Debug-only, advisory client reports. No device identifiers, credentials or remote lookups. */
final class ClientDiagnostics {
    private final SessionLog log;
    private final String owner;
    private boolean hardwareReceived;
    ClientDiagnostics(SessionLog log,int clientOid,int accountId,String username){
        this.log=log;owner="account="+text(username)+" (#"+accountId+"); clientOid="+clientOid;
    }
    static void joined(SessionLog log,Socket socket,int session,AccountStore.Account account,
            HandshakeCodec.Request request,String language,String region,long elapsed){
        if(!log.debugEnabled())return;
        log.debug("Client joined; session="+session+"; accountId="+account.id()+"; account="+text(account.username())
            +"; gm-at-login="+account.gameMaster()+"; ip="+socket.getInetAddress().getHostAddress()+"; port="+socket.getPort()
            +"; transport=TCP; client-build="+text(request.version())+"; timezone="+text(request.timezone())
            +"; language="+text(language)+"; reported-region="+text(region)+"; country=unavailable; login-ms="+Math.max(0,elapsed));
    }
    /** Native KnightMarshaller 10: seven nullable strings, fullscreen boolean, UI scale float.
     * ProjectXApp.eo supplies OS name/version, CPU identifier, Java version, os.arch,
     * desktop resolution and window resolution. These are untrusted diagnostics only. */
    void hardware(List<Object> args)throws SessionDecoder.Rejected {
        if(args.size()!=9)throw new SessionDecoder.Rejected("Invalid client hardware report");
        for(int i=0;i<7;i++)if(args.get(i)!=null&&!(args.get(i) instanceof String))throw new SessionDecoder.Rejected("Invalid client hardware string");
        if(!(args.get(7) instanceof Boolean)||!(args.get(8) instanceof Float scale)||!Float.isFinite(scale))throw new SessionDecoder.Rejected("Invalid client hardware display fields");
        if(hardwareReceived)return;hardwareReceived=true;
        if(!log.debugEnabled())return;
        log.debug("Client hardware; "+owner
            +"; os="+text((String)args.get(0))+" (build "+text((String)args.get(1))+")"
            +"; cpu="+text((String)args.get(2))
            +"; java="+text((String)args.get(3))+" ("+text((String)args.get(4))+")"
            +"; screen="+text((String)args.get(5))+"; window="+text((String)args.get(6))
            +"; mode="+((Boolean)args.get(7)?"fullscreen":"windowed")+"; ui-scale="+scale
            +"; gpu=unavailable; ram=unavailable");
    }
    /** Bounded, quoted untrusted text for both pre-authentication and joined diagnostics. */
    static String text(String value){
        if(value==null||value.isBlank()||value.equals("???"))return "unknown";
        var out=new StringBuilder("\"");int length=Math.min(value.length(),256);
        for(int i=0;i<length;i++){
            char c=value.charAt(i);int type=Character.getType(c);
            if(Character.isISOControl(c)||type==Character.FORMAT||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR)c=' ';
            if(c=='\\'||c=='\"')out.append('\\');out.append(c);
        }
        if(value.length()>length)out.append("...");return out.append('\"').toString();
    }
}

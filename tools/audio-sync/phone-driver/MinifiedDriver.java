package com.aliflix.validation;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Development-only, no app/library class references or shared test runtime. */
public final class MinifiedDriver extends Instrumentation {
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result = new Bundle(); Activity activity = null; ServerSocket server = null;
        try {
            Context context = getTargetContext();
            SharedPreferences corrections=context.getSharedPreferences("subtitle-audio-corrections",Context.MODE_PRIVATE);
            Set<String> previousKeys=new HashSet<>(corrections.getAll().keySet());
            File root = context.getExternalFilesDir(null);
            byte[] bytes = Files.readAllBytes(new File(root, "audio-sync-validation.wav").toPath());
            JSONArray cues = new JSONArray(Files.readString(new File(root, "audio-sync-validation.json").toPath()));
            StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
            for (int i=0;i<cues.length();i++) { JSONArray cue=cues.getJSONArray(i); vtt.append(stamp(cue.getDouble(0)+47)).append(" --> ").append(stamp(cue.getDouble(1)+47)).append('\n').append(cue.getString(2)).append("\n\n"); }
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            final ServerSocket listening = server;
            new Thread(() -> { while (!listening.isClosed()) { try { final Socket socket=listening.accept(); new Thread(() -> serve(socket,bytes)).start(); } catch (IOException e) { break; } } }).start();
            JSONObject request = new JSONObject().put("url", "http://127.0.0.1:"+server.getLocalPort()+"/speech.wav").put("mimeType","audio/wav")
                .put("referer","https://fixture.aliflix.test/").put("userAgent","Aliflix validation").put("cookie", "")
                .put("title","Minified streaming speech acceptance").put("playing",true).put("positionMs",0)
                .put("subtitlesVtt",vtt.toString()).put("subtitleLanguage","ar").put("subtitleLabel","Arabic fixture");
            File payload = new File(context.getCacheDir(), "native-request-"+UUID.randomUUID()+".json"); Files.writeString(payload.toPath(),request.toString());
            activity = startActivitySync(new Intent().setClassName(context,"com.aliflix.app.player.NativePlayerActivity")
                .putExtra("requestFile",payload.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor("Audio & Subtitles", 30000, true);
            waitFor("Sync with Audio",30000,true);
            long began=SystemClock.elapsedRealtime(); boolean collecting=false;
            while (SystemClock.elapsedRealtime()-began<180000) {
                AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), "Synced");
                if (node!=null) break;
                if (find(getUiAutomation().getRootInActiveWindow(),"Collecting Evidence")!=null) collecting=true;
                if (find(getUiAutomation().getRootInActiveWindow(),"Unable to Verify")!=null) throw new AssertionError("Minified app rejected fixture");
                Thread.sleep(500);
            }
            if (find(getUiAutomation().getRootInActiveWindow(),"Synced")==null || !collecting) throw new AssertionError("One-tap minified collection did not finish");
            JSONObject verified=null;
            for(String key:corrections.getAll().keySet()) if(!previousKeys.contains(key)) { JSONObject candidate=new JSONObject(corrections.getString(key,"{}")); if(candidate.has("offset")) verified=candidate; }
            if(verified==null || Math.abs(verified.getDouble("offset")+47)>.6 || Math.abs(verified.getDouble("rate")-1)>.0001)throw new AssertionError("Incorrect minified correction: "+verified);
            waitFor("Reset",5000,true);
            result.putString("result","PASS: fully minified native app, one tap, collecting, synced, reset; offset="+verified.getDouble("offset")+",confidence="+verified.getDouble("confidence")+",elapsedMs="+(SystemClock.elapsedRealtime()-began));
            Files.writeString(new File(root,"minified-sync-device.txt").toPath(),result.getString("result"));
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("result", "FAIL: "+error); StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace)); result.putString("stack",trace.toString()); finish(Activity.RESULT_CANCELED,result); }
        finally { if (activity!=null) { final Activity current=activity; runOnMainSync(current::finish); } if(server!=null) try{server.close();}catch(IOException ignored){} }
    }
    private String stamp(double value) { long ms=(long)(value*1000); return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000); }
    private void waitFor(String label,long timeout,boolean click) throws Exception {
        long began=SystemClock.elapsedRealtime();
        while(SystemClock.elapsedRealtime()-began<timeout) {
            AccessibilityNodeInfo node=find(getUiAutomation().getRootInActiveWindow(),label);
            if(node!=null) { if(click) { while(node!=null&&!node.isClickable()) node=node.getParent(); if(node==null)throw new AssertionError("Cannot click "+label); if(!node.isEnabled()){Thread.sleep(200);continue;} if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+label); } return; }
            Thread.sleep(200);
        }
        throw new AssertionError("Missing control: "+label+" tree="+tree(getUiAutomation().getRootInActiveWindow()));
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label) {
        if(node==null)return null;
        if(label.contentEquals(node.getText()==null?"":node.getText()) || label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){ AccessibilityNodeInfo found=find(node.getChild(i),label); if(found!=null)return found; } return null;
    }
    private String tree(AccessibilityNodeInfo node) { if(node==null)return "none"; StringBuilder s=new StringBuilder().append(node.getText()).append('|').append(node.getContentDescription()).append(';'); for(int i=0;i<node.getChildCount();i++)s.append(tree(node.getChild(i)));return s.toString(); }
    private void serve(Socket socket,byte[] bytes) { try(socket) {
        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)); String line=reader.readLine(); int start=0;
        while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("range: bytes="))start=Integer.parseInt(line.substring(13).split("-")[0]);
        OutputStream out=socket.getOutputStream(); String headers="HTTP/1.1 "+(start==0?"200 OK":"206 Partial Content")+"\r\nContent-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: "+(bytes.length-start)+"\r\n"+(start==0?"":"Content-Range: bytes "+start+"-"+(bytes.length-1)+"/"+bytes.length+"\r\n")+"Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(bytes,start,bytes.length-start);out.flush();
    }catch(Exception ignored){} }
}

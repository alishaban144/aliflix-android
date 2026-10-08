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
    private boolean fps;
    private boolean real;
    private boolean terminator;
    private boolean lifecycle;
    private boolean automatic;
    private int scene;
    private String restoreManual;
    public void onCreate(Bundle args) { super.onCreate(args); automatic=args!=null&&"true".equals(args.getString("automaticTerminator")); fps=args!=null&&"true".equals(args.getString("fps")); real=args!=null&&"true".equals(args.getString("real")); terminator=args!=null&&"true".equals(args.getString("terminator")); lifecycle=args!=null&&"true".equals(args.getString("lifecycle")); scene=args==null?0:Integer.parseInt(args.getString("sceneIndex","0")); restoreManual=args==null?null:args.getString("restoreManual"); if(scene<0||scene>2)throw new IllegalArgumentException("Invalid scene"); start(); }
    public void onStart() {
        Bundle result = new Bundle(); Activity activity = null; ServerSocket server = null; SharedPreferences settings = null; Integer originalManual = null;
        try {
            Context context = getTargetContext();
            SharedPreferences corrections=context.getSharedPreferences("subtitle-audio-corrections",Context.MODE_PRIVATE);
            Map<String,?> previousValues=new HashMap<>(corrections.getAll());
            settings=context.getSharedPreferences("aliflix_player_settings",Context.MODE_PRIVATE);
            if(restoreManual!=null) { settings.edit().putInt("subtitle_delay_tenths",Integer.parseInt(restoreManual)).commit(); Thread.sleep(1500); result.putString("result","PASS: restored captured manual delay"); finish(Activity.RESULT_OK,result); return; }
            int manualTenths=settings.getInt("subtitle_delay_tenths",0);
            originalManual=manualTenths;
            double manualDelay=manualTenths/10.0;
            File root = context.getExternalFilesDir(null);
            if(automatic) {
                activity=startActivitySync(new Intent().setClassName(context,"com.aliflix.app.player.NativePlayerActivity")
                    .putExtra("selection",Files.readString(new File(root,"automatic-terminator-private/selection.json").toPath()))
                    .putExtra("autoSubtitles",true).putExtra("subtitleLanguage","en").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitFor("Audio & Subtitles",30000,true);
                waitFor("Audio & subtitles",5000,false);
                if(find(getUiAutomation().getRootInActiveWindow(),"Synced")!=null)waitFor("Reset",5000,true);
                waitFor("Sync with Audio",90000,false);
                getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
                android.media.session.MediaController controller;
                try { controller=context.getSystemService(android.media.session.MediaSessionManager.class).getActiveSessions(null).stream().filter(it->context.getPackageName().equals(it.getPackageName())).findFirst().orElseThrow(); }
                finally { getUiAutomation().dropShellPermissionIdentity(); }
                // No stream requests, replacement captions, expected offsets or
                // detector evidence are supplied to the fully optimized app.
                for(long start:new long[]{1275619,3196058}) {
                    controller.getTransportControls().seekTo(start); controller.getTransportControls().play();
                    awaitPlayback(controller,start);
                    long until=SystemClock.elapsedRealtime()+90000;
                    while(SystemClock.elapsedRealtime()<until && controller.getPlaybackState().getPosition()<start+48000)Thread.sleep(100);
                    if(controller.getPlaybackState().getPosition()<start+48000)throw new AssertionError("Normal played dialogue did not arrive");
                }
                if(find(getUiAutomation().getRootInActiveWindow(),"Synced")!=null)waitFor("Reset",5000,true);
                previousValues=new HashMap<>(corrections.getAll());
                waitFor("Sync with Audio",5000,true); long began=SystemClock.elapsedRealtime();
                waitFor("Synced",2000,false); long elapsed=SystemClock.elapsedRealtime()-began;
                JSONObject verified=null;String key=null;
                for(String item:corrections.getAll().keySet()) if(!Objects.equals(previousValues.get(item),corrections.getString(item,"{}"))) {
                    JSONObject candidate=new JSONObject(corrections.getString(item,"{}"));
                    if(candidate.has("offset")&&!candidate.optBoolean("reset")){verified=candidate;key=item;}
                }
                if(verified==null||elapsed>=2000)throw new AssertionError("No applied timely correction");
                if(settings.getInt("subtitle_delay_tenths",0)!=manualTenths)throw new AssertionError("Manual delay changed");
                JSONObject receipt=new JSONObject().put("state","Synced").put("elapsedMs",elapsed).put("correction",verified)
                    .put("manualDelay",manualDelay).put("version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);
                Files.writeString(new File(root,"automatic-terminator-private/minified-receipt.json").toPath(),receipt.toString());
                waitFor("Reset",5000,true);waitFor("Sync with Audio",5000,false);
                if(!new JSONObject(corrections.getString(key,"{}")).optBoolean("reset"))throw new AssertionError("Reset did not clear correction");
                result.putString("result","PASS: normal automatic English captions, minified app, applied correction, Reset, manual preserved; "+receipt);
                finish(Activity.RESULT_OK,result);return;
            }
            byte[] bytes = real || terminator ? new byte[0] : Files.readAllBytes(new File(root, lifecycle ? "minified-audio-tracks.mp4" : "audio-sync-validation.wav").toPath());
            JSONArray cues = terminator ? new JSONArray(new JSONObject(Files.readString(new File(root,"terminator-scene-"+scene+".json").toPath())).getString("truth")) : real ? new JSONArray(new JSONObject(Files.readString(new File(root,"quick-sync-real-film-evidence.json").toPath())).getString("truth")) : new JSONArray(Files.readString(new File(root, "audio-sync-validation.json").toPath()));
            double expectedRate = fps ? 25.0/24.0 : 1.0;
            double expectedDelay = terminator ? new double[]{-9.25,6.75,-14.5}[scene] : real ? 7.25 : 47.0;
            StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
            for (int i=0;i<cues.length();i++) { JSONArray cue=cues.getJSONArray(i); vtt.append(stamp(cue.getDouble(0)/expectedRate+expectedDelay)).append(" --> ").append(stamp(cue.getDouble(1)/expectedRate+expectedDelay)).append('\n').append(cue.getString(2)).append("\n\n"); }
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            final ServerSocket listening = server;
            new Thread(() -> { while (!listening.isClosed()) { try { final Socket socket=listening.accept(); new Thread(() -> serve(socket,bytes)).start(); } catch (IOException e) { break; } } }).start();
            JSONObject request = new JSONObject().put("url", real ? "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov" : "http://127.0.0.1:"+server.getLocalPort()+"/speech.wav").put("mimeType",real ? "video/quicktime" : "audio/wav")
                .put("referer",real ? "https://download.blender.org/" : "https://fixture.aliflix.test/").put("userAgent","Aliflix validation").put("cookie", "")
                .put("title",real ? "Tears of Steel — minified playback validation" : "Minified streaming speech acceptance").put("playing",true).put("positionMs",0)
                .put("subtitlesVtt",vtt.toString()).put("subtitleLanguage",real ? "en" : "ar").put("subtitleLabel",real ? "Official English captions, shifted 7.25 seconds" : "Arabic fixture");
            if (terminator) {
                request = new JSONObject(Files.readString(new File(root,"terminator-source-private.json").toPath()));
                request.remove("selectionJson");
                request.put("positionMs",new long[]{1275370,3192580,3424390}[scene]).put("playing",true).put("preferEmbeddedSubtitles",false)
                    .put("subtitlesVtt",vtt.toString()).put("subtitleLanguage","en").put("title","Terminator — minified sync validation");
            }
            if (lifecycle) request.put("mimeType","video/mp4").put("title","Minified audio and seek validation").put("subtitleLanguage","en")
                .put("subtitlesVtt","WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nUnrelated caption without matching speech.\n\n00:00:12.000 --> 00:00:15.000\nAnother unrelated caption for rejection.\n\n00:00:23.000 --> 00:00:27.000\nNo valid dialogue clock exists.\n\n00:00:33.000 --> 00:00:36.000\nNever report a guessed correction.\n");
            File payload = new File(context.getCacheDir(), "native-request-"+UUID.randomUUID()+".json"); Files.writeString(payload.toPath(),request.toString());
            activity = startActivitySync(new Intent().setClassName(context,"com.aliflix.app.player.NativePlayerActivity")
                .putExtra("requestFile",payload.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor("Audio & Subtitles", 30000, true);
            waitFor("Audio & subtitles", 5000, false);
            if(find(getUiAutomation().getRootInActiveWindow(),"Synced")!=null)waitFor("Reset",5000,true);
            waitFor("Sync with Audio",30000,false);
            previousValues=new HashMap<>(corrections.getAll());
            if(lifecycle) {
                getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
                android.media.session.MediaController controller;
                try { controller=context.getSystemService(android.media.session.MediaSessionManager.class).getActiveSessions(null).stream().filter(it->context.getPackageName().equals(it.getPackageName())).findFirst().orElseThrow(); }
                finally { getUiAutomation().dropShellPermissionIdentity(); }
                waitFor("French",5000,true); waitSelected("French"); awaitPlayback(controller,-1);
                waitFor("English",5000,true); waitSelected("English"); awaitPlayback(controller,-1);
                controller.getTransportControls().seekTo(45000); awaitPlayback(controller,45000);
                controller.getTransportControls().seekTo(10000); awaitPlayback(controller,10000);
                waitFor("Sync with Audio",5000,true); long tap=SystemClock.elapsedRealtime();
                while(SystemClock.elapsedRealtime()-tap<2000 && find(getUiAutomation().getRootInActiveWindow(),"Not enough dialogue yet")==null && find(getUiAutomation().getRootInActiveWindow(),"Couldn't match this dialogue")==null && find(getUiAutomation().getRootInActiveWindow(),"Synced")==null)Thread.sleep(50);
                if(find(getUiAutomation().getRootInActiveWindow(),"Synced")!=null || find(getUiAutomation().getRootInActiveWindow(),"Syncing…")!=null)throw new AssertionError("Seek/track change produced a false or late result");
                if(find(getUiAutomation().getRootInActiveWindow(),"Not enough dialogue yet")==null && find(getUiAutomation().getRootInActiveWindow(),"Couldn't match this dialogue")==null)throw new AssertionError("Missing actionable rejection");
                if(settings.getInt("subtitle_delay_tenths",0)!=manualTenths)throw new AssertionError("Manual delay changed");
                awaitPlayback(controller,10000);
                result.putString("result","PASS: fully minified audio changes English/French/English, forward/backward seeks, playing, no false success, manual preserved; elapsedMs="+(SystemClock.elapsedRealtime()-tap));
                Files.writeString(new File(root,"minified-lifecycle-device.txt").toPath(),result.getString("result"));
                finish(Activity.RESULT_OK,result); return;
            }
            // Evidence is collected automatically at normal speed, before one tap.
            Thread.sleep(terminator ? 33500 : real ? 85000 : (fps ? 200000 : 130000));
            waitFor("Sync with Audio",30000,true);
            long began=SystemClock.elapsedRealtime(); boolean collecting=false;
            while (SystemClock.elapsedRealtime()-began<2000) {
                AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), "Synced");
                if (node!=null) break;
                if (find(getUiAutomation().getRootInActiveWindow(),"Collecting Evidence")!=null) collecting=true;
                if (find(getUiAutomation().getRootInActiveWindow(),"Couldn't match this dialogue")!=null) throw new AssertionError("Minified app rejected fixture");
                Thread.sleep(100);
            }
            if (find(getUiAutomation().getRootInActiveWindow(),"Synced")==null) throw new AssertionError("One-tap minified synchronization did not finish within two seconds");
            JSONObject verified=null; String verifiedKey=null;
            for(String key:corrections.getAll().keySet()) if(!Objects.equals(previousValues.get(key),corrections.getString(key,"{}"))) { JSONObject candidate=new JSONObject(corrections.getString(key,"{}")); if(candidate.has("offset")&&!candidate.optBoolean("reset")&&candidate.optInt("schema")==4) { verified=candidate; verifiedKey=key; } }
            if(settings.getInt("subtitle_delay_tenths",0)!=manualTenths)throw new AssertionError("Manual delay changed");
            double rateTolerance = fps ? .6/Math.max(1,cues.getJSONArray(cues.length()-1).getDouble(1)) : .0004;
            if(verified==null || Math.abs(verified.getDouble("offset")+manualDelay+expectedDelay*expectedRate)>.6 || Math.abs(verified.getDouble("rate")-expectedRate)>rateTolerance)throw new AssertionError("Incorrect minified correction: "+verified);
            double maxCueError = 0;
            for(int i=0;i<cues.length();i++) for(int edge=0;edge<2;edge++) { double original=cues.getJSONArray(i).getDouble(edge); double error=Math.abs((original/expectedRate+expectedDelay)*verified.getDouble("rate")+verified.getDouble("offset")+manualDelay-original); maxCueError=Math.max(maxCueError,error); if(error>.6)throw new AssertionError("Incorrect corrected cue "+i); }
            long syncElapsed=SystemClock.elapsedRealtime()-began;
            if(syncElapsed>=2000)throw new AssertionError("Minified synchronization exceeded deadline: "+syncElapsed);
            waitFor("Reset",5000,true);
            waitFor("Sync with Audio",5000,false);
            if(!new JSONObject(corrections.getString(verifiedKey,"{}")).optBoolean("reset"))throw new AssertionError("Reset did not clear the automatic correction");
            result.putString("result","PASS: fully minified native app, one tap, synced, reset; version="+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName+",realFilm="+real+",terminator="+terminator+",scene="+scene+",normalSpeed=true,buffered=true,manual="+manualDelay+",offset="+verified.getDouble("offset")+",rate="+verified.getDouble("rate")+",confidence="+verified.getDouble("confidence")+",maxCueErrorMs="+Math.round(maxCueError*1000)+",elapsedMs="+syncElapsed);
            Files.writeString(new File(root,terminator?"minified-terminator-"+scene+"-device.txt":fps?"minified-fps-device.txt":"minified-sync-device.txt").toPath(),result.getString("result"));
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("result", "FAIL: "+error); StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace)); result.putString("stack",trace.toString()); finish(Activity.RESULT_CANCELED,result); }
        finally { if(settings!=null&&originalManual!=null&&settings.getInt("subtitle_delay_tenths",0)!=originalManual)settings.edit().putInt("subtitle_delay_tenths",originalManual).commit(); if (activity!=null) { final Activity current=activity; runOnMainSync(current::finish); getTargetContext().stopService(new Intent().setClassName(getTargetContext(),"com.aliflix.app.player.NativePlaybackService")); } if(server!=null) try{server.close();}catch(IOException ignored){} }
    }
    private String stamp(double value) { long ms=(long)(value*1000); return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000); }
    private void waitSelected(String label) throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until) {
            AccessibilityNodeInfo row=find(getUiAutomation().getRootInActiveWindow(),label);
            while(row!=null&&!row.isClickable())row=row.getParent();
            if(row!=null&&find(row,"Selected")!=null)return;
            Thread.sleep(100);
        }
        throw new AssertionError("Audio track not selected: "+label);
    }
    private void awaitPlayback(android.media.session.MediaController controller,long target) throws Exception {
        long until=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<until) {
            android.media.session.PlaybackState state=controller.getPlaybackState();
            if(state!=null&&state.getState()==android.media.session.PlaybackState.STATE_PLAYING && (target<0||state.getPosition()>=target-500&&state.getPosition()<target+8000))return;
            Thread.sleep(100);
        }
        throw new AssertionError("Playback did not resume after audio change/seek: "+controller.getPlaybackState());
    }
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

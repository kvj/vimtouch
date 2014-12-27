package kvj.app.vimtouch;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class InstallProgress extends Activity {
    public static final String LOG_TAG = "VIM Installation";
    private Uri mUri;
    private ProgressBar mProgressBar;
    private TextView mProgressText;

    private void installDefaultRuntime() {

        try{

            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream is = new DigestInputStream(getResources().openRawResource(R.raw.vim),md);
            installZip(is, null, "Default Runtime");


            // write md5 bytes
            File md5 = new File(getMD5Filename(this));
            FileWriter fout = new FileWriter(md5);

            BigInteger bi = new BigInteger(1, md.digest());
            String result = bi.toString(16);
            if (result.length() % 2 != 0) 
                result = "0"+result;
            Log.e(LOG_TAG, "compute md5 "+result);
            fout.write(result);
            fout.close();

        installZip(getResources().openRawResource(R.raw.terminfo),null, "Terminfo");
        File folder = new File(this.getApplicationContext().getFilesDir()+"/vim");
        if (!folder.exists()) { // Make folder
            try {
                folder.mkdirs();
            } catch (Exception e) {
                Log.e("folder", "Failed to create folder", e);
            }
        }

        installSysVimrc(this);

        } catch(Exception e) { 
            Log.e(LOG_TAG, "install vim runtime or compute md5 error", e); 
        }
    }

    private static String getVimrc(Activity activity) {
        return activity.getApplicationContext().getFilesDir()+"/vim/vimrc";
    }

    private static String getMD5Filename( Activity activity) {
        return activity.getApplicationContext().getFilesDir()+"/vim.md5";
    }

    private static boolean checkMD5(Activity activity){
        File md5 = new File(getMD5Filename(activity));
        if(!md5.exists()){
            Log.w(LOG_TAG, "No MD5 file");
            return false;
        }

        // read md5 
        try{
            BufferedReader reader = new BufferedReader(new FileReader(md5));

            String saved = reader.readLine();
            Log.w(LOG_TAG, "Compare "+activity.getResources().getString(R.string.vim_md5)+" and "+saved);
            if(saved.equals(activity.getResources().getString(R.string.vim_md5))) return true;
        }catch(Exception e){
            Log.e(LOG_TAG, "MD5 file error", e);
        }

        return false;

    }

    public static boolean isInstalled(Activity activity){
        // check runtimes which not installed yet first
        File vimrc = new File(getVimrc(activity));
        if(vimrc.exists()){
            // Compare size to make sure the sys vimrc doesn't change
            try{
                
                if(fileSize(vimrc) != activity.getResources().openRawResource(R.raw.vimrc).available()){
                    installSysVimrc(activity);
                }
            }catch(Exception e){
                installSysVimrc(activity);
            }
            Log.w(LOG_TAG, "MD5 error: "+vimrc.getAbsolutePath()+" - "+checkMD5(activity));
            return checkMD5(activity);
        }

        Log.w(LOG_TAG, "No vimrc: "+vimrc.getAbsolutePath());
        return false;
    }
    
    private static long fileSize(File file) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) { // 2.3+
            return file.getTotalSpace();
        }
        return file.length();
    }

    public static void installSysVimrc(Activity activity) {

        File vimrc = new File(activity.getApplicationContext().getFilesDir()+"/vim/vimrc");

        try{
            BufferedInputStream is = new BufferedInputStream(activity.getResources().openRawResource(R.raw.vimrc));
            FileWriter fout = new FileWriter(vimrc);
            while(is.available() > 0){
                fout.write(is.read());
            }
            fout.close();
        } catch(Exception e) { 
            Log.e(LOG_TAG, "install vimrc", e); 
        } 

        File tmp = new File(activity.getApplicationContext().getFilesDir()+"/tmp");
        tmp.mkdir();
    }

    private void installLocalFile() {
        try {
            File file = new File(mUri.getPath());
            if(file.exists()){
                installZip(new FileInputStream(file), null, mUri.getPath());
            }
        }catch (Exception e){
            Log.e(LOG_TAG, "install " + mUri + " error " + e);
        }
    }

    static final int MSG_SET_TEXT = 1;
    static class ProgressHandler extends Handler {
        private final WeakReference<InstallProgress> mActivity;

        ProgressHandler(InstallProgress activity) {
            mActivity = new WeakReference<InstallProgress>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_SET_TEXT:
                String res = (String)msg.obj;
                InstallProgress activity = mActivity.get();

                if (activity != null)
                    activity.mProgressText.setText(res);
                    activity.setTitle(res);

                break;
            }
        }
    }
    private ProgressHandler mHandler = new ProgressHandler(this);

    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        try {
            mUri = getIntent().getData();
        }catch (Exception e){
            mUri = null;
        }

        setContentView(R.layout.installprogress);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
        mProgressText = (TextView) findViewById(R.id.progress_text);

        // Start lengthy operation in a background thread
        new Thread(new Runnable() {
            public void run() {
            Log.e(LOG_TAG, "install " + mUri );
                Context context = getApplicationContext();
                if(mUri == null){
                    installDefaultRuntime();
                /*
                }else if (mUri.getScheme().equals("http") || 
                          mUri.getScheme().equals("https") ||
                          mUri.getScheme().equals("ftp")) {
                    downloadRuntime(mUri);
                */
                }else if (mUri.getScheme().equals("backup")){
                    File output = new File(mUri.getPath());
                    backupAll(output);
                    showNotification(R.string.backup_finish);
                }else if (mUri.getScheme().equals("file")) {
                    installLocalFile();
                    showNotification(R.string.install_finish);
                }else if (mUri.getScheme().equals("content")){
                    try{
                        InputStream attachment = getContentResolver().openInputStream(mUri);
                        installZip(attachment, null, " from other application");
                        showNotification(R.string.install_finish);
                    }catch(Exception e){
                    }
                }

                // check plugins which not installed yet first

                finish();

            }
        }).start();
    }

    void showNotification(int desc) {
        String svc = NOTIFICATION_SERVICE;
        NotificationManager nm = (NotificationManager)getSystemService(svc);

        CharSequence from = "VimTouch";
        CharSequence message = getString(desc);

		Notification notif = new Notification(R.drawable.ic_vim_notification,
				message, System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this
        // notification
        Intent intent = new Intent(this, VimTouch.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                                                                intent, 0);

        notif.setLatestEventInfo(this, from, message, contentIntent);
        notif.defaults = Notification.DEFAULT_SOUND
                         | Notification.DEFAULT_LIGHTS;
        notif.flags |= Notification.FLAG_AUTO_CANCEL;

        nm.notify(0, notif);
    }

    private void installZip(InputStream is, FileWriter fw, String desc) {
        String dirname = getApplicationContext().getFilesDir().getPath();
        int progress = 0;
        mProgressBar.setProgress(0);
        String msgText = getString(R.string.installing);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_TEXT, msgText+" "+desc));
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(is));
        ZipEntry ze = null;
        int size;
        byte[] buffer = new byte[8192];

        try  {
            int total = is.available();
            mProgressBar.setMax(total);
            while ((ze = zin.getNextEntry()) != null) {
                Log.i(LOG_TAG, "Unzipping " + ze.getName());

                if(ze.isDirectory()) {
                    File file = new File(dirname+"/"+ze.getName());
                    if(!file.isDirectory())
                        file.mkdirs();
                    if(ze.getName().startsWith("bin/")) {
                        setReadableExecutable(file);
                    }
                } else {
                    File file = new File(dirname+"/"+ze.getName());
                    FileOutputStream fout = new FileOutputStream(file);
                    BufferedOutputStream bufferOut = new BufferedOutputStream(fout, buffer.length);
                    while((size = zin.read(buffer, 0, buffer.length)) != -1) {
                        bufferOut.write(buffer, 0, size);
                    }

                    bufferOut.flush();
                    bufferOut.close();
                    if(ze.getName().startsWith("bin/")) {
                        setReadableExecutable(file);
                    }
                    if(fw != null) fw.write(ze.getName()+"\n");
                }
                mProgressBar.setProgress(total-is.available());
            }

            byte[] buf = new byte[2048];
            while(is.available() > 0){
                is.read(buf);
                mProgressBar.setProgress(total-is.available());
            }
            buf = null;

            zin.close();
        } catch(Exception e) {
            Log.e(LOG_TAG, "unzip", e);
        }
    }

    private static void setReadableExecutable(File file) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) { // 2.3+
            file.setExecutable(true, false);
            file.setReadable(true, false);
            return;
        }
    }


    private void backupAll(File dest){
        String src = getApplicationContext().getFilesDir().getPath()+"/vim";

        mProgressBar.setProgress(0);
        String msgText = getString(R.string.backup);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_TEXT, msgText));

        try {
            ZipOutputStream zip = null;
            FileOutputStream fileWriter = null;

            fileWriter = new FileOutputStream(dest);
            zip = new ZipOutputStream(fileWriter);
            mProgressBar.setProgress(0);
            addFolderToZip("", src, zip);
            zip.flush();
            zip.close();
        }catch(Exception e){
        }
    }

    private void addFileToZip(String path, String srcFile, ZipOutputStream zip) throws Exception {
        File folder = new File(srcFile);
        if (folder.isDirectory()) {
            addFolderToZip(path, srcFile, zip);
        } else {
            byte[] buf = new byte[1024];
            int len;
            FileInputStream in = new FileInputStream(srcFile);
            zip.putNextEntry(new ZipEntry(path + "/" + folder.getName()));
            while ((len = in.read(buf)) > 0) {
                zip.write(buf, 0, len);
            }
        }
    }

    private void addFolderToZip(String path, String srcFolder, ZipOutputStream zip) throws Exception {
        File folder = new File(srcFolder);
        int total = folder.list().length;
        int done = 0;
        if (path.equals("")) {
            mProgressBar.setMax(total);
        }

        for (String fileName : folder.list()) {
            if (path.equals("")) {
                addFileToZip(folder.getName(), srcFolder + "/" + fileName, zip);
                mProgressBar.setProgress(++done);
            } else {
                addFileToZip(path + "/" + folder.getName(), srcFolder + "/" + fileName, zip);
            }
        }
    }

    private DownloadManager mDM;
    private long mEnqueue = -1;
    private BroadcastReceiver mReceiver = null;

    public void onDestroy() {
        if(mReceiver != null)
            unregisterReceiver(mReceiver);
        super.onDestroy();
    }
}

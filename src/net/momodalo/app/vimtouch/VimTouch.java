/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.momodalo.app.vimtouch;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.ClipboardManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.ScaleGestureDetector;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.content.DialogInterface;
import android.os.ParcelFileDescriptor;

import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.ColorScheme;

/**
 * A terminal emulator activity.
 */

public class VimTouch extends Activity {
    /**
     * Set to true to add debugging code and logging.
     */
    public static final boolean DEBUG = false;

    /**
     * Set to true to log each character received from the remote process to the
     * android log, which makes it easier to debug some kinds of problems with
     * emulating escape sequences and control codes.
     */
    public static final boolean LOG_CHARACTERS_FLAG = DEBUG && true;

    /**
     * Set to true to log unknown escape sequences.
     */
    public static final boolean LOG_UNKNOWN_ESCAPE_SEQUENCES = DEBUG && true;

    /**
     * The tag we use when logging, so that our messages can be distinguished
     * from other messages in the log. Public because it's used by several
     * classes.
     */
    public static final String LOG_TAG = "VimTouch";

    /**
     * Our main view. Displays the emulated terminal screen.
     */
    private TermView mEmulatorView;
    private VimTermSession mSession;
    private TermSettings mSettings;

    private LinearLayout mButtonBar;

    private int mControlKeyId = 0;

    private static final String TERM_FD = "TERM_FD";

    private final static String DEFAULT_SHELL = "/system/bin/sh -";
    private String mShell;

    private final static String DEFAULT_INITIAL_COMMAND =
        "export PATH=/data/local/bin:$PATH";
    private String mInitialCommand;

    private SharedPreferences mPrefs;

    private final static int COPY_ALL_ID = 0;
    private final static int PASTE_ID = 1;

    private String mUrl = null;

    View.OnClickListener mClickListener = new View.OnClickListener() {
        public void onClick(View v){
            TextView textview = (TextView)v;
            CharSequence cmd = textview.getText();
            if(cmd.charAt(0) == ':'){
                mSession.write(cmd.toString()+"\r");
            }else
                mSession.write(cmd.toString());
            Exec.updateScreen();
        }
    };

    private String getIntentUrl(Intent intent){
        if(intent == null || intent.getScheme() == null) return null;
        String url = null;
        if(intent.getScheme().equals("file")) {
            url = intent.getData().getPath();
        }else if (intent.getScheme().equals("content")){
              
             String tmpPath = "tmp";
             try {
                InputStream attachment = getContentResolver().openInputStream(intent.getData());

                if(attachment.available() > 50000){
                    tmpPath = "";
                    new AlertDialog.Builder(this).
                        setTitle(R.string.dialog_title_content_error).
                        setMessage(R.string.message_content_too_big).
                        show();
                    throw new IOException("file too big");
                }

                String attachmentFileName = "NoFile";
                if (intent != null && intent.getData() != null) {
                    Cursor c = getContentResolver().query( intent.getData(), null, null, null, null);
                    c.moveToFirst();
                    final int fileNameColumnId = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0)
                        attachmentFileName = c.getString(fileNameColumnId);
                }

                String str[] = attachmentFileName.split("\\.");

                File outFile;
                if(str.length >= 2)
                    outFile=File.createTempFile(str[0], "."+str[1], getDir(tmpPath, MODE_PRIVATE));
                else
                    outFile=File.createTempFile(attachmentFileName,"", getDir(tmpPath, MODE_PRIVATE));
                tmpPath = outFile.getAbsolutePath();

                FileOutputStream f = new FileOutputStream(outFile);
                                                                          
                byte[] buffer = new byte[1024];
                while (attachment.read(buffer) > 0){
                    f.write(buffer);
                }
                f.close();
            }catch (Exception e){
                tmpPath = null;
                Log.e(VimTouch.LOG_TAG, e.toString());
            }

            url = tmpPath;
        }
        return url;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Log.e(VimTouch.LOG_TAG, "onCreate");

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings = new TermSettings(getResources(), mPrefs);

        mUrl = getIntentUrl(getIntent());

        setContentView(R.layout.term_activity);

        mButtonBar = (LinearLayout) findViewById(R.id.button_bar);
        TextView button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(R.string.title_keyboard);
        button.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ doToggleSoftKeyboard(); }
        });
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(R.string.title_esc);
        button.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ mSession.write(27); }
        });
        mButtonBar.addView((View)button);

        Resources res = getResources();

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik1", res.getString(R.string.default_normal_quick1)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik2", res.getString(R.string.default_normal_quick2)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik3", res.getString(R.string.default_normal_quick3)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik4", res.getString(R.string.default_normal_quick4)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik5", res.getString(R.string.default_normal_quick5)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik6", res.getString(R.string.default_normal_quick6)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik7", res.getString(R.string.default_normal_quick7)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik8", res.getString(R.string.default_normal_quick8)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        button = (TextView)getLayoutInflater().inflate(R.layout.quickbutton, (ViewGroup)mButtonBar, false);
        button.setText(mPrefs.getString("normal_qucik9", res.getString(R.string.default_normal_quick9)));
        button.setOnClickListener(mClickListener);
        mButtonBar.addView((View)button);

        if(checkVimRuntime())
            startEmulator();

        Exec.vimtouch = this;
    }

    private TermView createEmulatorView(VimTermSession session) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        TermView emulatorView = new TermView(this, session, metrics);

        registerForContextMenu(emulatorView);
        return emulatorView;
    }

    private void startEmulator() {
        LinearLayout layout = (LinearLayout)findViewById(R.id.main_layout);
        mSession = new VimTermSession( getApplicationContext().getFilesDir().getPath() ,mUrl, mSettings, "");
        mEmulatorView = createEmulatorView(mSession);
        layout.addView(mEmulatorView);

        mEmulatorView.updateSize(true);
        Exec.updateScreen();
    }

    public String getVimrc() {
        return getApplicationContext().getFilesDir()+"/vim/vimrc";
    }
    
    private boolean checkVimRuntime(){
        File vimrc = new File(getVimrc());
        if(vimrc.exists()) return true;
        
        Intent intent = new Intent(getApplicationContext(), InstallProgress.class);
        startActivityForResult(intent, 0);

        return false;
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(checkVimRuntime())
            startEmulator();
    }

    @Override
    public void onStop() {
        Log.e(VimTouch.LOG_TAG, "on stop.");
        /*
        if (mTermFd != null) {
            try{
                //mSession.write((":q!\r").getBytes("UTF-8"));
            }catch (Exception e){
            }
            Exec.close(mTermFd);
            mTermFd = null;
        }
        */
        super.onStop();
    }

    private void restart() {
        startActivity(getIntent());
        finish();
    }

    private void write(String data) {
        mSession.write(data);
    }

    private void updatePrefs() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        ColorScheme colorScheme = new ColorScheme(mSettings.getColorScheme());

        mEmulatorView.setDensity(metrics);
        mEmulatorView.updatePrefs(mSettings);

        mSession.updatePrefs(mSettings);
    }

    @Override
    public void onResume() {
        Log.e(VimTouch.LOG_TAG, "on resume.");
        super.onResume();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings.readPrefs(mPrefs);
        updatePrefs();
        mEmulatorView.onResume();
    }

    @Override
    public void onPause() {
        Log.e(VimTouch.LOG_TAG, "on pause.");
        super.onPause();
        mEmulatorView.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.e(VimTouch.LOG_TAG, "on new intent.");
        String url = getIntentUrl(intent);
        if(mSession == null){
            mUrl = url;
            startEmulator();
            return;
        }
        if(url == mUrl || url == "") return;
        
        Exec.doCommand("new "+url);
        Exec.updateScreen();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.e(VimTouch.LOG_TAG, "on configuration changed");
        mEmulatorView.updateSize(true);
    }

    private final int MSG_DIALOG = 1;
    private class DialogObj {
        public int type;
        public String title;
        public String message;
        public String buttons;
        public int def_button;
        public String textfield;
    };
    
    private Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_DIALOG:
                DialogObj obj = (DialogObj)msg.obj;
                realShowDialog(obj.type, obj.title, obj.message, obj.buttons, obj.def_button, obj.textfield);
                break;
            default:
                super.handleMessage(msg);
            }
        }
    };

    public void showDialog(int type, String title, String message, String buttons, int def_button, String textfield) {
        DialogObj obj = new DialogObj();
        obj.type = type;
        obj.title = title;
        obj.message = message;
        obj.buttons = buttons;
        obj.def_button = def_button;
        obj.textfield = textfield;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DIALOG, obj));
    }

    private AlertDialog.Builder actionBuilder;

    private void realShowDialog(int type, String title, String message, String buttons, int def_button, String textfield) {
        buttons = buttons.replaceAll("&", "");
        String button_array[] = buttons.split("\n");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
               .setTitle(title)
               .setPositiveButton(button_array[def_button], new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Exec.resultDialogDefaultState();
                    }
               })
               .setNegativeButton("More", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        AlertDialog actdialog = actionBuilder.create();
                        actdialog.show();
                    }
               });
               /*
        switch(type) {
            case 1:
                break;
            case 2:
                break;
            default:
                dialog = null;
        }
        */

        AlertDialog dialog = builder.create();
        dialog.show();

        actionBuilder = new AlertDialog.Builder(VimTouch.this);
        actionBuilder.setTitle(title)
                  .setCancelable(false)
                  .setItems(button_array, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog2, int item) {
                            Exec.resultDialogState(item+1);
                        }
                  });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_preferences) {
            doPreferences();
        } else if (id == R.id.menu_vimrc) {
            Exec.doCommand("new "+getVimrc());
            Exec.updateScreen();
        } else if (id == R.id.menu_toggle_soft_keyboard) {
            doToggleSoftKeyboard();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);
      menu.setHeaderTitle(R.string.edit_text);
      menu.add(0, COPY_ALL_ID, 0, R.string.copy_all);
      menu.add(0, PASTE_ID, 0,  R.string.paste);
      if (!canPaste()) {
          menu.getItem(PASTE_ID).setEnabled(false);
      }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
          switch (item.getItemId()) {
          case COPY_ALL_ID:
            doCopyAll();
            return true;
          case PASTE_ID:
            doPaste();
            return true;
          default:
            return super.onContextItemSelected(item);
          }
        }

    private boolean canPaste() {
        ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip.hasText()) {
            return true;
        }
        return false;
    }

    private void doPreferences() {
        startActivity(new Intent(this, VimTouchPreferences.class));
    }

    private void doResetTerminal() {
        restart();
    }

    private void doEmailTranscript() {
        // Don't really want to supply an address, but
        // currently it's required, otherwise we get an
        // exception.
        String addr = "user@example.com";
        Intent intent =
                new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"
                        + addr));

        intent.putExtra("body", mSession.getTranscriptText().trim());
        startActivity(intent);
    }

    private void doCopyAll() {
        ClipboardManager clip = (ClipboardManager)
             getSystemService(Context.CLIPBOARD_SERVICE);
        clip.setText(mSession.getTranscriptText().trim());
    }

    private void doPaste() {
        ClipboardManager clip = (ClipboardManager)
         getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence paste = clip.getText();
        mSession.write(paste.toString());
    }

    private void doToggleSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager)
            getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,0);

    }
}

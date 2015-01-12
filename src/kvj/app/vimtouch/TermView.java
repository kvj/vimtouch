package kvj.app.vimtouch;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Field;

import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;

public class TermView extends EmulatorView implements
        GestureDetector.OnGestureListener {
    private TermSession mSession;
    private int mTopRow = 0; //we don't use termnial scroll
    private GestureDetector mGestureDetector;
    private ScaleDetectorCompat mScaleDetector;
    private boolean mSingleTapESC;
    private boolean mTouchGesture;
    VimSettings mSettings;
    private boolean mInserted = false;
    private Runnable mCheckRunnable;
    private Handler mCheckHandler;
    private int mCheckCount = 0;
    private VimInputConnection mInputConnection = null;
    private boolean mIMEComposing = false;
    private boolean mZoomBottom = true;
    private int mFontSize = 0;

    private static final int FLING_REFRESH_PERIOD = 50;
    private static final int SCREEN_CHECK_PERIOD = 1000;
    private static final int CURSOR_BLINK_PERIOD = 1000;
    private static final int VISUAL_MODE_PERIOD = 1000;

    public interface OnZoomListener {
        void onZoom(boolean on);
    }

    private OnZoomListener mZoomListener = null;
    public void setOnZoomListener(OnZoomListener listen) {
        mZoomListener = listen;
    }


    public boolean checkInsertMode() {
        boolean b = Exec.isInsertMode();
        if (b != mInserted){
            Context context = getContext();
            InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.restartInput(TermView.this);
            mInserted = b;
            return true;
        }
        return false;
    }

    public TermView(Context context, TermSession session, DisplayMetrics metrics) {
        super(context, session, metrics);
        mSession = session;
        mGestureDetector = new GestureDetector(context, this);
        mScaleDetector = null;
        mScaleDetector = new ScaleDetectorCompat(this);
        mCheckRunnable = new Runnable() {
            public void run() {
                if(!checkInsertMode() && mCheckCount < 10)
                    mCheckHandler.postDelayed(mCheckRunnable, 100);
                mCheckCount++;
            }
        };
        mCheckHandler = new Handler();
    }

    public void updatePrefs(VimSettings settings, ColorScheme scheme) {
        if (scheme == null) {
            scheme = new ColorScheme(settings.getColorScheme());
        }

        if(mFontSize != settings.getFontSize()){
            setTextSize(settings.getFontSize());
            mFontSize = settings.getFontSize();
        }
        setCursorStyle(settings.getCursorStyle(), settings.getCursorBlink());
        setUseCookedIME(settings.useCookedIME());
        mIMEComposing = settings.useCookedIME();
        setColorScheme(scheme);
        setBackKeyCharacter(settings.getBackKeyCharacter());
        setControlKeyCode(settings.getControlKeyCode());
        setFnKeyCode(settings.getFnKeyCode());
        mZoomBottom = settings.getZoomBottom();
        mSingleTapESC = settings.getSingleTapESC();
        mTouchGesture = settings.getTouchGesture();

        mSettings = settings;
    }

    public void updatePrefs(VimSettings settings) {
        updatePrefs(settings, null);
    }

    // Begin GestureDetector.OnGestureListener methods
    /**
     * Our message handler class. Implements a periodic callback.
     */
    private final Handler mHandler = new Handler();

    public boolean onScroll(MotionEvent e1, MotionEvent e2,
            float distanceX, float distanceY) {
        /*
        distanceY += mScrollRemainder;
        int deltaRows = (int) (distanceY / getCharacterHeight());
        mScrollRemainder = distanceY - deltaRows * getCharacterHeight();
        mTopRow =
            Math.min(0, Math.max(-(mTranscriptScreen
                    .getActiveTranscriptRows()), mTopRow + deltaRows));
        */
        invalidate();
        return true;
    }

    float mScaleSpan = -1.0f;

    public boolean onSingleTapUp(MotionEvent ev) {
        Exec.mouseDown( mDownY, mDownX);
        Exec.mouseUp( mDownY, mDownX);
        if(mSingleTapESC)mSession.write(27);
        return true;
    }

    private float mVelocity = 0;

    private Runnable mFlingRun = new Runnable() {
        public void run() {
            Exec.scrollBy((int)mVelocity);
            if(mVelocity > 0){
                mVelocity -= mVelocity>2?2:mVelocity;
            }else{
                mVelocity -= mVelocity<-2?-2:mVelocity;
            }
            if(mVelocity == 0){
                if(mInputConnection!=null)mInputConnection.notifyTextChange();
                return;
            }
            mHandler.postDelayed(this, FLING_REFRESH_PERIOD);
        }
    };

    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
            float velocityY) {
        mVelocity = -velocityY/(10*getCharacterHeight());
        mHandler.postDelayed(mFlingRun, FLING_REFRESH_PERIOD);
        return true;
    }

    int mDownX;
    int mDownY;

    public boolean onDown(MotionEvent ev) {
        float y = ev.getY();
        float x = ev.getX();
        int fingers = ev.getPointerCount();
        mLastY = y;
        mHandler.removeCallbacks(mFlingRun);

        return true;
    }

    // End GestureDetector.OnGestureListener methods
    float mLastY = -1;
    float mLastX = -1;
    int mVisualMode = 0;
    
    private void toggleVisualMode() {
        if(!Exec.isInsertMode()){
            switch(mVisualMode){
                case 0:
                    mVisualMode = 1;
                    mSession.write("v");
                    break;
                case 1:
                    mVisualMode = 2;
                    mSession.write((char) ('v' - 'a' + '\001'));
                    break;
                case 2:
                    mVisualMode = 1;
                    mSession.write("v");
                    break;
                default:
                    mVisualMode = 1;
                    mSession.write("v");
                    break;
            }
        }
    }

    private Runnable mVisualRun = new Runnable() {
        public void run() {
            toggleVisualMode();
        }
    };

    boolean mZoom = false;
    float mZoomX = 0.0f;
    float mZoomY = 0.0f;
    public void setZoom(boolean z){
        mZoom = z;
        if(mZoomListener != null) mZoomListener.onZoom(z);
    }

    public boolean getZoom(){
        return mZoom;
    }

    public void onLongPress(MotionEvent ev) {
        float y = ev.getY();
        float x = ev.getX();
        setZoom(true);
        mZoomX = x;
        mZoomY = y;

        mLastY = -1;
        Exec.mouseDown( (int)(y/getCharacterHeight()), (int)(x/getCharacterWidth()));
        Exec.mouseUp( (int)(y/getCharacterHeight()), (int)(x/getCharacterWidth()));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        checkInsertMode();

        mHandler.removeCallbacks(mVisualRun);

        if(getSelectingText())
            return super.onTouchEvent(ev);
        float y = ev.getY();
        float x = ev.getX();
        int action = ev.getAction();
        int fingers = ev.getPointerCount();

        if(action == MotionEvent.ACTION_DOWN && fingers == 1){
            mLastY = y;
            mLastX = x;
            mDownX = (int)(x/getCharacterWidth());
            mDownY = (int)(y/getCharacterHeight());

        }else if (action == MotionEvent.ACTION_MOVE && fingers == 1 && mScaleSpan < 0.0){
            mZoomX = x;
            mZoomY = y;

            if(mLastX != -1 && Math.abs(x-mLastX) > getCharacterWidth() * 5 && !getZoom()){
                setZoom(true);
                mLastY = -1;
                int cursorX = (int)(x/getCharacterWidth());
                int cursorY = (int)(y/getCharacterHeight());
                Exec.setCursorPos(cursorY, cursorX);
            } else if(mLastY != -1 && ( mLastX == -1 || (Math.abs(y-mLastY) > getCharacterHeight() * 2)) && !getZoom()){
                int scrolls = (int)((mLastY - y)/getCharacterHeight());
                if(mTouchGesture){
                    if(mLastX != -1){
                        int cursorX = (int)(x/getCharacterWidth());
                        int cursorY = (int)(y/getCharacterHeight());
                        Exec.setCursorPos(cursorY, cursorX);
                    }
                    //Exec.mouseDown( mDownY, mDownX);
                    if(scrolls != 0)Exec.scrollBy(scrolls);
                    if(mInputConnection!=null)mInputConnection.notifyTextChange();
                }
                if(scrolls != 0)mLastY = y;
                mLastX = -1;
            }else if (getZoom()){
                long time = ev.getEventTime();
                int state = Exec.getState();

                if(mVisualMode > 0){
                    Exec.mouseDrag( (int)(y/getCharacterHeight()), (int)(x/getCharacterWidth()));
                    Exec.mouseDown( (int)(y/getCharacterHeight()), (int)(x/getCharacterWidth()));
                }else{
                    int cursorX = (int)(x/getCharacterWidth());
                    int cursorY = (int)(y/getCharacterHeight());
                    Exec.setCursorPos(cursorY, cursorX);
                }
                Exec.updateScreen();
                mHandler.postDelayed(mVisualRun, VISUAL_MODE_PERIOD);
            }
        }else if(action == MotionEvent.ACTION_UP){
            if(getZoom())
                Exec.mouseUp( (int)(y/getCharacterHeight()), (int)(x/getCharacterWidth()));
            mLastY = -1;
            mLastX = -1;
            setZoom(false);
            invalidate();
            mVisualMode = 0;
            if(mInputConnection!=null)mInputConnection.notifyTextChange();
        }
        if (mTouchGesture && mScaleSpan < 0.0) 
            mGestureDetector.onTouchEvent(ev);
        if (null != mScaleDetector) { //
            return mScaleDetector.onTouchEvent(ev);
        }
        return true;
    }

    public boolean dispatchKeyEvent(KeyEvent event){
        boolean b = super.dispatchKeyEvent(event);
        lateCheckInserted();
        return b;
    }

    public void lateCheckInserted(){
        //FIXME check the vim State change lately
        mCheckHandler.removeCallbacks(mCheckRunnable);
        if(!checkInsertMode()){
            mCheckCount = 0;
            mCheckHandler.postDelayed(mCheckRunnable, 100);
        }
    }

    public InputConnection onCreateInputConnection (EditorInfo outAttrs) {
        if(!Exec.isInsertMode() || !mIMEComposing){
            mInputConnection = null;
            return super.onCreateInputConnection(outAttrs);
        }
        outAttrs.actionLabel = null;
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE;
        mInputConnection = new VimInputConnection(this);
        return mInputConnection;
    }

    public boolean toggleIMEComposing(){
        mIMEComposing = mIMEComposing?false:true;
        Context context = getContext();
        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.restartInput(TermView.this);
        return mIMEComposing;
    }


    /*
    public boolean onCheckIsTextEditor () {
        return true;
    }*/

    private float mScaleX = 1.0f;
    private float mScaleY = 1.0f;
    private float mScalePX;
    private float mScalePY;

    public void setScale(float sx, float sy, float px, float py){
        mScaleX = sx;
        mScaleY = sy;
        mScalePX = px;
        mScalePY = py;
    }
 
    protected void onDraw(Canvas canvas) {

        if(mScaleX != 1.0 || mScaleY != 1.0){
            canvas.scale(mScaleX,mScaleY,mScalePX,mScalePY);
        }

        super.onDraw(canvas);
        if(mZoom){
            float tx = 2 * getCharacterWidth() * ( 1 - 2 * mZoomX / getVisibleWidth());
            float h = getCharacterHeight()*2;
            canvas.scale(3.0f,3.0f,mZoomX, mZoomY);
            if(mZoomY > getVisibleHeight()/2){
                canvas.translate( tx, (-mZoomY)/3 + h);
            }else{
                canvas.translate( tx, (getVisibleHeight()-mZoomY)/3 - h);
            }
            canvas.clipRect(mZoomX - mZoomX/3-5, mZoomY-h, mZoomX+(getVisibleWidth()-mZoomX)/3+5,mZoomY+h);
            super.onDraw(canvas);
        }
        if(mZoomBottom && Exec.isCmdLine()){
            float cx = getVisibleWidth() - (Exec.getCursorCol()+2) * getCharacterWidth() * 3.0f;
            canvas.scale(3.0f,3.0f, cx < 0.0f? cx : 0.0f, getVisibleHeight() - getCharacterHeight());
            canvas.translate( cx < 0.0f? cx : 0.0f, (- getVisibleHeight() + getCharacterHeight() * 2)/3);
            super.onDraw(canvas);
        }
    }

    public float getCharacterWidth(){
        try {
            Field privateField = 
               EmulatorView.class.getDeclaredField("mCharacterWidth");
            privateField.setAccessible(true);

            return privateField.getFloat(this);
        }catch(Exception e){
            return 0.0f;
        }
    }

    public float getCharacterHeight(){
        try{
            Field privateField = 
               EmulatorView.class.getDeclaredField("mCharacterHeight");
            privateField.setAccessible(true);

            return (float) privateField.getInt(this);
        }catch(Exception e){
            return 0.0f;
        }
    }
}

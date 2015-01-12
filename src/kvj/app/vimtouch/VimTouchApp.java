package kvj.app.vimtouch;

import com.lazydroid.autoupdateapk.AutoUpdateApk;

import org.kvj.bravo7.ApplicationContext;
import org.kvj.bravo7.log.AndroidLogger;
import org.kvj.bravo7.log.Logger;

/**
 * Created by kvorobyev on 11/18/14.
 */
public class VimTouchApp extends ApplicationContext {

    private AutoUpdateApk autoUpdateApk = null;

    @Override
    protected void init() {
        autoUpdateApk = new AutoUpdateApk(this);
        autoUpdateApk.setUpdateInterval(AutoUpdateApk.DAYS);
        Logger.setOutput(new AndroidLogger());
        publishBean(new VimTouchRunner());
    }
}

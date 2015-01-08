package kvj.app.vimtouch.ext.manager.impl;

import android.content.Intent;

import org.kvj.bravo7.log.Logger;
import org.kvj.vimtouch.ext.IncomingTransfer;
import org.kvj.vimtouch.ext.OutgoingTransfer;
import org.kvj.vimtouch.ext.Transferable;
import org.kvj.vimtouch.ext.impl.EmptyTransferable;
import org.kvj.vimtouch.ext.impl.read.ListFieldReader;
import org.kvj.vimtouch.ext.impl.read.StringFieldReader;
import org.kvj.vimtouch.ext.manager.IntegrationExtension;
import org.kvj.vimtouch.ext.manager.IntegrationExtensionException;

import java.util.ArrayList;
import java.util.List;

import kvj.app.vimtouch.VimTouchApp;

/**
 * Created by kvorobyev on 12/30/14.
 */
public class WidgetExtension implements IntegrationExtension<WidgetExtension.WidgetInput, EmptyTransferable>{

    Logger logger = Logger.forInstance(this);

    @Override
    public String getType() {
        return "widget";
    }

    @Override
    public WidgetInput newInput() {
        return new WidgetInput();
    }

    @Override
    public EmptyTransferable process(WidgetInput input) throws IntegrationExtensionException {
        logger.d("New widget data:", input.dest, input.name, input.lines);
        if ("pebble".equals(input.dest)) { // Send Pebble Broadcast
            Intent intent = new Intent("kvj.pebble.MESSAGE");
            intent.putExtra("name", input.name);
            String title = input.lines.get(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < input.lines.size(); i++) { // $COMMENT
                if (i>0) {
                    sb.append("\n");
                }
                sb.append(input.lines.get(i));
            }
            intent.putExtra("title", title);
            intent.putExtra("message", sb.toString());
            intent.putExtra("vibro", 0);
            logger.d("Will send to pebble");
            VimTouchApp.getInstance().sendBroadcast(intent);
        }
        return new EmptyTransferable();
    }

    static class WidgetInput implements Transferable {

        ArrayList<String> lines = new ArrayList<String>();
        String dest = "";
        String name = "";

        @Override
        public void readFrom(IncomingTransfer t) {
            t.readAs("lines", new ListFieldReader<String>(new StringFieldReader() {
                @Override
                public void set(String value) {
                }
            }) {
                @Override
                public void set(List<String> value) {
                }

                @Override
                public void add(String value) {
                    lines.add(value);
                }
            });
            t.readAs("dest", new StringFieldReader() {
                @Override
                public void set(String value) {
                    dest = value;
                }
            });
            t.readAs("name", new StringFieldReader() {
                @Override
                public void set(String value) {
                    name = value;
                }
            });
        }

        @Override
        public void writeTo(OutgoingTransfer t) {
        }
    }
}

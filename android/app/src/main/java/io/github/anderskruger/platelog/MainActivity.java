package io.github.anderskruger.platelog;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SessionPlugin.class);   // the lock-screen card, see SessionCard
        super.onCreate(savedInstanceState);
    }
}

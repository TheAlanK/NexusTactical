package com.nexustactical;

import com.fs.starfarer.api.BaseModPlugin;
import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;
import com.nexusui.overlay.NexusFrame;

public class TacticalModPlugin extends BaseModPlugin {

    @Override
    public void onGameLoad(boolean newGame) {
        NexusFrame.registerPageFactory(new NexusPageFactory() {
            public String getId() { return "nexus_tactical"; }
            public String getTitle() { return "Tactical"; }
            public NexusPage create() { return new TacticalPage(); }
        });
    }
}

package combine;

import arc.scene.ui.layout.Table;
import mindustry.world.blocks.storage.StorageBlock;

public class CombinedStorageBlock extends StorageBlock {

  public CombinedStorageBlock(String name) {
    super(name);
  }

  public class CombinedStorageBuild extends StorageBuild {
    @Override
    public void display(Table table) {
      super.display(table);
      ComboNet.addNetworkDisplay(table, this, 1);
    }
  }
}

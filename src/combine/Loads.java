package combine;

import mindustry.content.Blocks;
import mindustry.world.Block;

import static combine.BlockCloner.*;

public class Loads {
  public static Block cGraphitePress, cMultiPress, cSiliconSmelter, cSiliconCrucible, cKiln,
      cPlastaniumCompressor, cPhaseWeaver, cSurgeSmelter, cCryofluidMixer,
      cPyratiteMixer, cBlastMixer, cMelter, cSeparator, cDisassembler,
      cSporePress, cPulverizer, cCoalCentrifuge, cIncinerator;
  public static Block cMechanicalDrill, cPneumaticDrill, cLaserDrill, cBlastDrill;

  public static void load() {
    cMechanicalDrill = drill(Blocks.mechanicalDrill, CombinedDrill.Mode.drill);
    cPneumaticDrill = drill(Blocks.pneumaticDrill, CombinedDrill.Mode.drill);
    cLaserDrill = drill(Blocks.laserDrill, CombinedDrill.Mode.drill);
    cBlastDrill = drill(Blocks.blastDrill, CombinedDrill.Mode.burst);

    cGraphitePress = crafter(Blocks.graphitePress, CombinedCrafter.Mode.generic);
    cMultiPress = crafter(Blocks.multiPress, CombinedCrafter.Mode.generic);
    cSiliconSmelter = crafter(Blocks.siliconSmelter, CombinedCrafter.Mode.generic);
    cSiliconCrucible = crafter(Blocks.siliconCrucible, CombinedCrafter.Mode.attribute);
    cKiln = crafter(Blocks.kiln, CombinedCrafter.Mode.generic);
    cPlastaniumCompressor = crafter(Blocks.plastaniumCompressor, CombinedCrafter.Mode.generic);
    cPhaseWeaver = crafter(Blocks.phaseWeaver, CombinedCrafter.Mode.generic);
    cSurgeSmelter = crafter(Blocks.surgeSmelter, CombinedCrafter.Mode.generic);
    cCryofluidMixer = crafter(Blocks.cryofluidMixer, CombinedCrafter.Mode.generic);
    cPyratiteMixer = crafter(Blocks.pyratiteMixer, CombinedCrafter.Mode.generic);
    cBlastMixer = crafter(Blocks.blastMixer, CombinedCrafter.Mode.generic);
    cMelter = crafter(Blocks.melter, CombinedCrafter.Mode.generic);
    cSeparator = crafter(Blocks.separator, CombinedCrafter.Mode.separator);
    cDisassembler = crafter(Blocks.disassembler, CombinedCrafter.Mode.separator);
    cSporePress = crafter(Blocks.sporePress, CombinedCrafter.Mode.generic);
    cPulverizer = crafter(Blocks.pulverizer, CombinedCrafter.Mode.generic);
    cCoalCentrifuge = crafter(Blocks.coalCentrifuge, CombinedCrafter.Mode.generic);
    cIncinerator = crafter(Blocks.incinerator, CombinedCrafter.Mode.generic);

    // 如果某个组合版需要覆盖 drawer，在 copyFields 之后写：
    // ((CombinedCrafter)cCryofluidMixer).drawer = new DrawMulti(...);
  }

  private static Block crafter(Block orig, CombinedCrafter.Mode mode) {
    CombinedCrafter c = createCombo(orig, CombinedCrafter.class);
    c.mode = mode;
    copyFields(orig, c);
    return c;
  }

  private static Block drill(Block orig, CombinedDrill.Mode mode) {
    CombinedDrill d = createCombo(orig, CombinedDrill.class);
    d.mode = mode;
    copyFields(orig, d);
    return d;
  }
}

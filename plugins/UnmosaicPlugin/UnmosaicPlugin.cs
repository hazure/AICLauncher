using BepInEx;
using AliceInCradle.ModInterface;

namespace AliceInCradle.UnmosaicPlugin
{
    [BepInPlugin("com.aliceincradle.unmosaic", "UnmosaicPlugin", "0.1.0")]
    public class UnmosaicPlugin : BaseUnityPlugin, IModLifecycle
    {
        private void Awake()
        {
            OnReady();
        }

        public void OnReady()
        {
            PatchRegistry.Register("unmosaic", "nel.MosaicShower", "FnDrawMosaic");
            Diagnostics.Log("UnmosaicPlugin ready (runtime patch, zero-pollution)");
        }

        public void OnFrame(float deltaTime) { }

        public void OnExit()
        {
            Diagnostics.Log("UnmosaicPlugin unloaded");
        }
    }
}

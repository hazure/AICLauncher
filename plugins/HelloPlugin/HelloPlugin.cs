using BepInEx;

namespace AliceInCradle.HelloPlugin
{
    [BepInPlugin("com.aliceincradle.helloplugin", "HelloPlugin", "0.1.0")]
    public class HelloPlugin : BaseUnityPlugin
    {
        private void Awake()
        {
            Logger.LogInfo("HelloPlugin initialized");
        }
    }
}

using System;

namespace AliceInCradle.ModInterface
{
    /// <summary>
    /// Minimal mod interface library (PLAN.md Chapter 5.2):
    /// lifecycle, patch registry, diagnostics, and version gate.
    /// </summary>
    public interface IModLifecycle
    {
        void OnReady();
        void OnFrame(float deltaTime);
        void OnExit();
    }

    public static class PatchRegistry
    {
        public static void Register(string patchId, string targetType, string targetMethod)
        {
            Diagnostics.Log($"Registered patch {patchId} -> {targetType}.{targetMethod}");
        }
    }

    public static class Diagnostics
    {
        public static void Log(string message)
        {
            Console.WriteLine($"[ModInterface] {message}");
        }
    }

    public static class VersionGate
    {
        public static bool IsCompatible(string currentVersion, string minVersion)
        {
            return string.CompareOrdinal(currentVersion, minVersion) >= 0;
        }
    }
}

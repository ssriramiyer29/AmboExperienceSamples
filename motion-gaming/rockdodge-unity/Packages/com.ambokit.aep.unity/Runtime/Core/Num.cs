using System;

namespace AmboKit.AEP.Core
{
    /// <summary>
    /// Float maths for the renderer-independent experience core.
    ///
    /// Exists so the experience rules do not depend on UnityEngine.Mathf or Vector2.
    /// The dependency was arithmetic convenience rather than engine integration, but it
    /// still made the rules unusable outside Unity, and ADR-0001 rule 2 requires the same
    /// rules to run under any renderer. Semantics match Mathf exactly, so behaviour and
    /// the Android port are unchanged.
    /// </summary>
    internal static class Num
    {
        public static float Max(float a, float b) => a > b ? a : b;
        public static float Min(float a, float b) => a < b ? a : b;
        public static float Abs(float v) => v < 0f ? -v : v;

        /// <summary>Mathf.Clamp semantics: min wins when min > max.</summary>
        public static float Clamp(float value, float min, float max)
        {
            if (value < min) return min;
            if (value > max) return max;
            return value;
        }

        /// <summary>Mathf.Lerp semantics, including the clamped t.</summary>
        public static float Lerp(float a, float b, float t)
        {
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            return a + (b - a) * t;
        }

        public static float Distance(float ax, float ay, float bx, float by)
        {
            float dx = ax - bx, dy = ay - by;
            return (float)Math.Sqrt(dx * dx + dy * dy);
        }
    }
}

using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;

namespace AmboKit.AEP.Unity.Rendering
{
    // Self-contained QR encoder for AmboJoin URLs.
    // Fixed QR Version 10, Error Correction Level L, mask 0.
    // Supports UTF-8 payloads up to 271 bytes, which comfortably covers AmboJoin URLs.
    public static class SimpleQrCode
    {
        const int Version = 10;
        const int Size = 57;
        const int DataCodewords = 274;
        const int EccPerBlock = 18;
        static readonly int[] BlockDataLengths = { 68, 68, 69, 69 };

        public static Texture2D CreateTexture(string text, int pixelsPerModule = 8, int quietZone = 4)
        {
            var matrix = Encode(text);
            int modules = Size + quietZone * 2;
            int px = modules * pixelsPerModule;
            var tex = new Texture2D(px, px, TextureFormat.RGBA32, false);
            tex.filterMode = FilterMode.Point;
            tex.wrapMode = TextureWrapMode.Clamp;
            var pixels = new Color32[px * px];
            var white = new Color32(255, 255, 255, 255);
            var black = new Color32(0, 0, 0, 255);
            for (int i = 0; i < pixels.Length; i++) pixels[i] = white;
            for (int y = 0; y < Size; y++)
            {
                for (int x = 0; x < Size; x++)
                {
                    if (!matrix[y, x]) continue;
                    int sx = (x + quietZone) * pixelsPerModule;
                    int sy = (Size - 1 - y + quietZone) * pixelsPerModule;
                    for (int dy = 0; dy < pixelsPerModule; dy++)
                        for (int dx = 0; dx < pixelsPerModule; dx++)
                            pixels[(sy + dy) * px + (sx + dx)] = black;
                }
            }
            tex.SetPixels32(pixels);
            tex.Apply(false, false);
            return tex;
        }

        static bool[,] Encode(string text)
        {
            byte[] payload = Encoding.UTF8.GetBytes(text ?? string.Empty);
            if (payload.Length > 271) throw new ArgumentException("AmboJoin URL is too long for the built-in QR encoder.");
            byte[] data = BuildData(payload);
            byte[] allCodewords = AddErrorCorrectionAndInterleave(data);

            var modules = new bool[Size, Size];
            var function = new bool[Size, Size];
            DrawFunctionPatterns(modules, function);
            DrawCodewords(modules, function, allCodewords);
            DrawFormatBits(modules, function, 0);
            DrawVersion(modules, function);
            return modules;
        }

        static byte[] BuildData(byte[] payload)
        {
            var bits = new List<bool>(DataCodewords * 8);
            AppendBits(bits, 0b0100, 4); // byte mode
            AppendBits(bits, payload.Length, 16); // version 10 uses 16-bit count for byte mode
            foreach (byte b in payload) AppendBits(bits, b, 8);
            int capacity = DataCodewords * 8;
            int terminator = Math.Min(4, capacity - bits.Count);
            for (int i = 0; i < terminator; i++) bits.Add(false);
            while ((bits.Count & 7) != 0) bits.Add(false);

            var result = new List<byte>(DataCodewords);
            for (int i = 0; i < bits.Count; i += 8)
            {
                int value = 0;
                for (int j = 0; j < 8; j++) value = (value << 1) | (bits[i + j] ? 1 : 0);
                result.Add((byte)value);
            }
            bool toggle = false;
            while (result.Count < DataCodewords)
            {
                result.Add(toggle ? (byte)0x11 : (byte)0xEC);
                toggle = !toggle;
            }
            return result.ToArray();
        }

        static byte[] AddErrorCorrectionAndInterleave(byte[] data)
        {
            byte[] divisor = ReedSolomonDivisor(EccPerBlock);
            var blocks = new List<byte[]>();
            var eccs = new List<byte[]>();
            int offset = 0;
            foreach (int len in BlockDataLengths)
            {
                var block = new byte[len];
                Array.Copy(data, offset, block, 0, len);
                offset += len;
                blocks.Add(block);
                eccs.Add(ReedSolomonRemainder(block, divisor));
            }

            var result = new List<byte>(346);
            int maxData = 69;
            for (int i = 0; i < maxData; i++)
                foreach (var block in blocks)
                    if (i < block.Length) result.Add(block[i]);
            for (int i = 0; i < EccPerBlock; i++)
                foreach (var ecc in eccs) result.Add(ecc[i]);
            return result.ToArray();
        }

        static void DrawFunctionPatterns(bool[,] m, bool[,] f)
        {
            DrawFinder(m, f, 3, 3);
            DrawFinder(m, f, Size - 4, 3);
            DrawFinder(m, f, 3, Size - 4);

            for (int i = 8; i < Size - 8; i++)
            {
                SetFunction(m, f, 6, i, (i & 1) == 0);
                SetFunction(m, f, i, 6, (i & 1) == 0);
            }

            int[] align = { 6, 28, 50 };
            foreach (int cy in align)
            foreach (int cx in align)
            {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == 50) || (cx == 50 && cy == 6)) continue;
                for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    SetFunction(m, f, cx + dx, cy + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
            }

            // Reserve format information modules.
            for (int i = 0; i < 9; i++)
            {
                if (i != 6)
                {
                    f[8, i] = true;
                    f[i, 8] = true;
                }
            }
            for (int i = 0; i < 8; i++) f[8, Size - 1 - i] = true;
            for (int i = 0; i < 7; i++) f[Size - 1 - i, 8] = true;
            SetFunction(m, f, 8, Size - 8, true); // dark module

            // Reserve version information modules.
            for (int i = 0; i < 6; i++)
            for (int j = 0; j < 3; j++)
            {
                f[i, Size - 11 + j] = true;
                f[Size - 11 + j, i] = true;
            }
        }

        static void DrawFinder(bool[,] m, bool[,] f, int cx, int cy)
        {
            for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++)
            {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= Size || y >= Size) continue;
                int dist = Math.Max(Math.Abs(dx), Math.Abs(dy));
                SetFunction(m, f, x, y, dist != 2 && dist != 4);
            }
        }

        static void DrawCodewords(bool[,] m, bool[,] f, byte[] codewords)
        {
            var bits = new List<bool>(codewords.Length * 8);
            foreach (byte b in codewords) AppendBits(bits, b, 8);
            int bitIndex = 0;
            bool upward = true;
            for (int right = Size - 1; right >= 1; right -= 2)
            {
                if (right == 6) right--;
                for (int vert = 0; vert < Size; vert++)
                {
                    int y = upward ? Size - 1 - vert : vert;
                    for (int j = 0; j < 2; j++)
                    {
                        int x = right - j;
                        if (f[y, x]) continue;
                        bool bit = bitIndex < bits.Count && bits[bitIndex];
                        bitIndex++;
                        bool mask = ((x + y) & 1) == 0; // mask 0
                        m[y, x] = bit ^ mask;
                    }
                }
                upward = !upward;
            }
        }

        static void DrawFormatBits(bool[,] m, bool[,] f, int mask)
        {
            int data = (1 << 3) | mask; // ECC L = 01
            int rem = BchRemainder(data << 10, 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;
            Func<int, bool> bit = i => ((bits >> i) & 1) != 0;

            for (int i = 0; i <= 5; i++) SetFunction(m, f, 8, i, bit(i));
            SetFunction(m, f, 8, 7, bit(6));
            SetFunction(m, f, 8, 8, bit(7));
            SetFunction(m, f, 7, 8, bit(8));
            for (int i = 9; i < 15; i++) SetFunction(m, f, 14 - i, 8, bit(i));
            for (int i = 0; i < 8; i++) SetFunction(m, f, Size - 1 - i, 8, bit(i));
            for (int i = 8; i < 15; i++) SetFunction(m, f, 8, Size - 15 + i, bit(i));
            SetFunction(m, f, 8, Size - 8, true);
        }

        static void DrawVersion(bool[,] m, bool[,] f)
        {
            int rem = BchRemainder(Version << 12, 0x1F25);
            int bits = (Version << 12) | rem;
            for (int i = 0; i < 18; i++)
            {
                bool bit = ((bits >> i) & 1) != 0;
                int a = Size - 11 + (i % 3);
                int b = i / 3;
                SetFunction(m, f, a, b, bit);
                SetFunction(m, f, b, a, bit);
            }
        }

        static void SetFunction(bool[,] m, bool[,] f, int x, int y, bool value)
        {
            m[y, x] = value;
            f[y, x] = true;
        }

        static int BchRemainder(int value, int poly)
        {
            int polyDegree = Degree(poly);
            while (Degree(value) >= polyDegree) value ^= poly << (Degree(value) - polyDegree);
            return value;
        }

        static int Degree(int value)
        {
            int d = -1;
            while (value != 0) { value >>= 1; d++; }
            return d;
        }

        static void AppendBits(List<bool> bits, int value, int count)
        {
            for (int i = count - 1; i >= 0; i--) bits.Add(((value >> i) & 1) != 0);
        }

        static byte[] ReedSolomonDivisor(int degree)
        {
            var result = new byte[degree];
            result[degree - 1] = 1;
            byte root = 1;
            for (int i = 0; i < degree; i++)
            {
                for (int j = 0; j < degree; j++)
                {
                    result[j] = Multiply(result[j], root);
                    if (j + 1 < degree) result[j] ^= result[j + 1];
                }
                root = Multiply(root, 0x02);
            }
            return result;
        }

        static byte[] ReedSolomonRemainder(byte[] data, byte[] divisor)
        {
            var result = new byte[divisor.Length];
            foreach (byte b in data)
            {
                byte factor = (byte)(b ^ result[0]);
                for (int i = 0; i < result.Length - 1; i++) result[i] = result[i + 1];
                result[result.Length - 1] = 0;
                for (int i = 0; i < result.Length; i++) result[i] ^= Multiply(divisor[i], factor);
            }
            return result;
        }

        static byte Multiply(byte x, byte y)
        {
            int z = 0, a = x, b = y;
            for (int i = 0; i < 8; i++)
            {
                if ((b & 1) != 0) z ^= a;
                b >>= 1;
                a = (a << 1) ^ (((a & 0x80) != 0) ? 0x11D : 0);
            }
            return (byte)z;
        }
    }
}

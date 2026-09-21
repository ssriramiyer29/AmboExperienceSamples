using System;
using System.Collections.Generic;

namespace AmboKit.AEP.Core
{
    public enum TrackingState { Acquiring, Tracked, Limited, Lost }
    public enum PlayerAction { Detected, Lost, MoveLeft, MoveRight, ReturnCenter, Jump, Crouch, LeftHandRaised, RightHandRaised }
    public enum ExperienceState { Created, Starting, WaitingForPlayer, Calibrating, Ready, Running, Paused, Completed, Failed, Closed }

    [Serializable]
    public struct PlayerMotionState
    {
        public float Horizontal;
        public bool Jumping;
        public bool Crouching;
        public TrackingState TrackingState;
        public PlayerMotionState(float horizontal, bool jumping, bool crouching, TrackingState trackingState)
        { Horizontal = horizontal; Jumping = jumping; Crouching = crouching; TrackingState = trackingState; }
    }

    [Serializable]
    public struct PoseLandmark
    {
        public string Name; public float X; public float Y; public float Z; public float Confidence;
        public PoseLandmark(string name, float x, float y, float z, float confidence)
        { Name=name; X=x; Y=y; Z=z; Confidence=confidence; }
    }

    public sealed class PoseFrame
    {
        public string ParticipantId; public long Sequence; public long SourceTimestampUs; public long HostReceivedTimestampMs;
        public TrackingState TrackingState;
        public readonly Dictionary<string, PoseLandmark> Landmarks = new Dictionary<string, PoseLandmark>(StringComparer.OrdinalIgnoreCase);
        public bool TryGet(string name, out PoseLandmark value) => Landmarks.TryGetValue(name, out value);
    }

    public sealed class LivePersonFrame
    {
        public string ParticipantId; public long Sequence; public long SourceTimestampUs; public long HostReceivedTimestampMs;
        public byte[] PngBytes; public int Width; public int Height;
    }

    public sealed class JoinInfo { public string Code; public string Url; public string QrUrl; public string ExpiresAt; }

    public interface IExperienceEngine<T>
    {
        void Start(); void OnPlayerAction(PlayerAction action); T Tick(long deltaMs); T Snapshot();
    }
    public interface IExperienceRenderer<in T> { void Render(T snapshot); }
}

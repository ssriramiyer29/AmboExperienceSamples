# Clean separation check

The Rock Dodge package must NOT contain any of the following:
- EmbeddedAmboGateway.cs
- ProductionAmboKitHostBackend.cs
- CameraPoseCapability.cs
- LivePersonCapability.cs
- AmboSession.cs
- HostSdk directory

These belong to the external AmboKit Unity Host package.

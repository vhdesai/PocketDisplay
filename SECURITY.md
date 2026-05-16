# Security

## Security Model

PocketDisplay is designed for local USB use by default. The normal deployment model is:
- Windows server running on the host PC
- Android phone connected over trusted USB
- ADB reverse forwarding from phone localhost to the PC server

In this mode, the transport stays on the local machine/USB link and does not require network exposure.

## Authentication

No application-level authentication is required for the default USB workflow because trust is delegated to ADB device authorization. Only devices explicitly approved through the Android USB debugging prompt can use ADB reverse on the host.

## ADB Trusted Device Model

ADB access depends on the Android trusted device model:
- USB debugging must be enabled manually
- the user must approve the host fingerprint on the phone
- the authorization can be revoked from Developer Options at any time

If a device is no longer trusted, revoke USB debugging authorizations on the phone and remove the device from the host.

## Firewall Considerations

The server can bind to `0.0.0.0` for flexibility, but USB mode should remain the default. If Wi-Fi testing is enabled, consider restricting firewall rules to trusted local networks only.

Recommended practices:
- prefer USB over Wi-Fi
- allow only the required listening port in Windows Firewall
- avoid exposing the service to public or untrusted networks

## Local System Impact

Touch events received by the server are injected as Windows mouse events. Only run the server when you trust the connected device and user.

## Reporting Security Issues

If you discover a security issue, report it privately to the maintainers instead of opening a public issue with exploit details.

# Virtual Display Driver Setup on Windows

## Overview

A virtual display driver creates a software-only monitor that Windows treats like a real display. This is useful when you need an extra screen for streaming, screen capture, testing, remote access, Sunshine, or headless workflows without attaching a physical monitor.

For Android camera or capture workflows, a virtual display gives Windows a stable target display that can be positioned, sized, and configured independently of the primary monitor.

## Recommended Driver

**Recommended:** [Virtual-Display-Driver by itsmikethetech](https://github.com/itsmikethetech/Virtual-Display-Driver)

Why this driver:
- It is the most actively maintained IDD-based virtual display driver.
- It supports modern Windows 10/11 workflows.
- It supports configurable resolutions and refresh rates.
- Recent releases include signed packages, a control app, and manual install files.

> Note: the GitHub project currently publishes releases from the `VirtualDrivers/Virtual-Display-Driver` repository, but the `itsmikethetech` URL is still the easiest reference point.

## Installation

### 1. Download the latest release

1. Open the releases page: `https://github.com/itsmikethetech/Virtual-Display-Driver/releases`
2. Download the latest release package.
3. Extract it to a local folder such as `C:\VirtualDisplayDriver`.

Look for one of these package types:
- A **setup executable** such as `Virtual.Display.Driver-...-setup-x64.exe`
- A **control app** such as `VDDControl-...exe` or **Virtual Driver Control**
- A **driver-only/manual install** zip containing files such as `MttVDD.inf`

### 2. Enable driver signing support

Use **one** of these methods.

#### Option A - Enable test signing mode

Open an elevated Command Prompt or PowerShell and run:

```powershell
bcdedit /set testsigning on
```

Then reboot Windows.

Use this when:
- You are installing an unsigned or test-signed build
- You are following the manual INF installation path and the release notes require it

#### Option B - Install the release certificate instead

Some newer releases are signed and can be installed without permanently enabling test signing.

Typical certificate-based flow:
1. Extract the release zip.
2. Locate the driver catalog file, for example `mttvdd.cat`.
3. Import the included certificate chain into **Local Machine > Trusted Publishers**.
4. Reboot if Windows still blocks the driver.

If the release includes a control app or installer, prefer that path first because it usually handles the signing details for you.

### 3. Install via Device Manager (manual INF method)

If you are using the manual driver package:

1. Press `Win + X` -> **Device Manager**
2. In Device Manager, select the computer name at the top.
3. Choose **Action** -> **Add legacy hardware**
4. Click **Next**
5. Select **Install the hardware that I manually select from a list (Advanced)**
6. Click **Next**
7. Select **Display adapters**
8. Click **Next**
9. Click **Have Disk**
10. Browse to the extracted driver folder and select `MttVDD.inf`
11. Complete the wizard
12. Reboot if the display does not appear immediately

### 4. Alternative - use the included installer or control app

If the release includes a setup executable or the **Virtual Driver Control** app:

1. Run the installer or control app as Administrator.
2. Click **Install** if prompted.
3. Reboot if Windows does not show the virtual monitor right away.

This is the easiest option on recent releases.

## Configuration for 2280x1080 @ 60Hz

The driver reads supported modes from its configuration. On current releases, this is typically the XML file:

```text
C:\VirtualDisplayDriver\vdd_settings.xml
```

Some older setups or helper tools may expose similar settings through the registry or companion app, but the XML file is the main place to edit manual resolutions in current releases.

### Add custom resolution 2280x1080

1. Open `C:\VirtualDisplayDriver\vdd_settings.xml` in a text editor running as Administrator.
2. Find the `<resolutions>` section.
3. Add a new `<resolution>` entry for `2280x1080`.

Example:

```xml
<resolutions>
    <resolution>
        <width>1920</width>
        <height>1080</height>
        <refresh_rate>60</refresh_rate>
    </resolution>
    <resolution>
        <width>2280</width>
        <height>1080</height>
        <refresh_rate>60</refresh_rate>
    </resolution>
    <resolution>
        <width>2560</width>
        <height>1440</height>
        <refresh_rate>60</refresh_rate>
    </resolution>
</resolutions>
```

### Set refresh rate to 60Hz

You can set refresh rate in either of these places:

1. **Per resolution** using `<refresh_rate>60</refresh_rate>` inside the custom mode entry
2. **Globally** using a `<g_refresh_rate>60</g_refresh_rate>` entry in the `<global>` section

Example global section:

```xml
<global>
    <g_refresh_rate>60</g_refresh_rate>
</global>
```

### Apply the change

After editing the config:
1. Save the file.
2. Disable and re-enable the virtual display driver, or reboot Windows.
3. Open **Settings -> System -> Display -> Advanced display** and confirm that `2280 x 1080` at `60 Hz` is available.

## Verification

After installation and configuration:

1. Open **Settings -> System -> Display**
2. Confirm Windows shows a new virtual monitor
3. Click **Identify** if needed to find it
4. Drag the virtual display so it is arranged correctly relative to the primary monitor
5. Select the virtual display and set:
   - **Display resolution:** `2280 x 1080`
   - **Refresh rate:** `60 Hz`
   - **Scale:** `100%`
6. Confirm applications can move onto the virtual monitor

## Troubleshooting

### Driver not loading

Common cause: **test signing is not enabled** for unsigned/test-signed builds.

Fix:
- Re-run `bcdedit /set testsigning on` in an elevated shell
- Reboot
- If using a signed release, try the installer/control app or install the certificate chain instead

### Display not appearing

Common causes:
- Windows has not re-enumerated the display stack yet
- Reboot is still required
- The driver installed but is disabled

Fix:
- Reboot Windows
- Check **Device Manager -> Display adapters** for the virtual driver
- Disable and re-enable the device if needed

### Resolution not available

Common cause: XML config syntax or placement is wrong.

Check:
- `<resolution>` entries are inside `<resolutions>`
- `<width>`, `<height>`, and `<refresh_rate>` are all present
- XML tags are correctly closed
- The config file was saved with valid XML syntax
- The driver was reloaded after editing

### How to uninstall

Use one of these methods:

#### Uninstall with the control app or installer
- Open the included control app as Administrator
- Choose **Uninstall**

#### Uninstall with Device Manager
1. Open **Device Manager**
2. Expand **Display adapters**
3. Right-click the virtual display driver
4. Choose **Uninstall device**
5. If shown, check **Delete the driver software for this device**
6. Reboot

If display priority becomes broken after driver changes, boot into **Safe Mode** and uninstall from there.

## Alternative Drivers

### IddSampleDriver

Microsoft's sample indirect display driver.

Pros:
- Useful as a reference implementation
- Good for driver developers and debugging

Cons:
- More complex setup
- Less convenient for end-user deployment
- Not the best choice if you just want a working virtual monitor quickly

### usbmmidd

An older virtual display driver that has been widely used in the past.

Pros:
- Historically popular
- Simpler for some legacy setups

Cons:
- Older approach
- Less actively maintained
- Usually not the best default choice for new Windows 10/11 setups

## Recommendation Summary

For most users, install **Virtual-Display-Driver by itsmikethetech**, use the included installer or control app when available, and manually add `2280x1080` at `60 Hz` in `C:\VirtualDisplayDriver\vdd_settings.xml` if that mode is not already exposed.
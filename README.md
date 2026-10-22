# FS Legal Size Scanner

**Making legal-size document scanning more accessible with a standard A4 flatbed scanner.**

FS Legal Size Scanner is a Java-based Windows desktop application designed to help scan documents larger than A4 using a conventional A4 flatbed scanner. It processes scan sections and combines them into a larger document.

The project was inspired by the practical cost of scanning legal documents and the challenges faced by advocates working in rural areas.

## Features

- **Legal-size scanning workflow:** Capture larger documents in sections using a compatible scanner.
- **Image processing:** Process scan images for alignment, rotation, cropping, and stitching.
- **Configurable scan settings:** Configure supported parameters such as DPI, brightness, colour mode, and page dimensions.
- **Scanner integration:** Work with compatible scanners and supported scanning backends.
- **Windows installer:** Install the application using the Microsoft Installer (MSI) package published with a release.
- **Open-source development:** Explore, modify, and improve the application under the terms of its licence.

Available features may vary by application version, scanner compatibility, and configuration.

## Download and Installation

Download FS Legal Size Scanner from the official GitHub Releases page:

**GitHub Releases:** https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases

### Installing on Windows

1. Open the [GitHub Releases page](https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases).
2. Select the release you want to install.
3. Download the `.msi` installer attached to that release.
4. Double-click the downloaded installer.
5. Follow the Windows Installer wizard to complete installation.
6. Launch FS Legal Size Scanner using the installed shortcut, if provided.

The application has been tested under a standard Windows user account without administrator privileges. Specific installation permissions may still depend on the Windows environment and installer configuration.

If Windows displays a security warning, verify that the installer was obtained from the official project repository before proceeding.

### System Requirements

- A supported version of Microsoft Windows.
- A compatible A4 flatbed scanner.
- Scanner drivers and any required scanning services.
- Any external components required by the selected workflow, unless included in the installer.

Consult the release documentation for version-specific requirements. Do not assume that an external dependency is included unless the relevant release confirms it.

### Uninstallation

To uninstall the application, open **Settings → Apps → Installed apps** in Windows, locate FS Legal Size Scanner, and select **Uninstall**.

Back up any documents or configuration files you want to retain before uninstalling.

## Using FS Legal Size Scanner

1. Connect the scanner and confirm that Windows recognises it.
2. Launch FS Legal Size Scanner.
3. Select an available scanner and configure the supported scan settings.
4. Follow the application's workflow to capture the required sections of the document.
5. Allow the application to process, align, and combine the scans.
6. Review the resulting document and save the output using the available options.

The exact controls and workflow may vary by application version and scanner backend. Consult the release documentation for version-specific instructions.

## Configuration and Local Data

The application uses a local `config.properties` file for configuration and scanner-related metadata. Depending on the version, settings may include:

- Scanner identification and selection.
- Scan resolution and colour mode.
- Brightness and image-processing parameters.
- Page dimensions.
- Alignment, cropping, and rotation settings.

The developer's local configuration is not intended to be distributed as part of the source repository because it may contain machine-specific scanner information.

Application configuration and temporary workspace data may be stored in the current Windows user's local application-data directory. Avoid publishing private paths, device addresses, or sensitive configuration values.

## Troubleshooting

### Scanner not detected

- Confirm that the scanner is powered on and connected.
- Check that Windows recognises the device.
- Verify that the required drivers and scanning services are installed.
- Confirm that the scanner is compatible with the application's scanning backend.

### Application does not start

- Confirm that the installation completed successfully.
- Check the release documentation for any required dependencies.
- Review any error messages or diagnostic output.
- If the problem persists, report the Windows version and application release.

### Image-processing or OCR errors

If your configured workflow requires ImageMagick, Tesseract OCR, or another external component, verify that the component is available and correctly configured.

### Incorrect alignment or stitching

- Check the orientation and dimensions of the scanned sections.
- Verify the supported alignment, cropping, and rotation settings.
- Follow the intended scanning order.
- If the problem persists, report reproducible steps and the application version.

Before sharing logs or configuration files, remove personal information, confidential document contents, credentials, and private device details.

## Development

The repository contains the Java source code and application resources.

```text
FS-Legal-Size-Scanner/
├── src/                 # Java source files
├── res/                 # Application images and icon resources
├── run.bat              # Windows launch script, if present
├── package.bat          # Application packaging script
├── installer.bat        # MSI installer build script
├── LICENSE.txt          # Project licence
└── README.txt           # Project documentation
```

The exact files in the repository may change as development progresses. Generated build outputs, packaged application files, and local configuration should be kept separate from the source files where appropriate.

### Building from Source

The repository includes Windows packaging scripts for building the application and its MSI installer. Review the scripts and their prerequisites before running them.

The packaged Windows installer is intended for end users. Developers can use the source repository to inspect, modify, build, and test the implementation.

## Third-Party Libraries and Credits

The project has involved the use or evaluation of external tools and technologies, including ImageMagick for image processing and Tesseract OCR for optical character recognition.

The exact dependencies included in a particular release should be determined from the corresponding source, packaging configuration, and release documentation.

Third-party components remain subject to their respective licences and attribution requirements. The licence of this project does not replace or override those licences.

## The Story Behind the Project

<details>
<summary>Why I built FS Legal Size Scanner</summary>

The idea behind FS Legal Size Scanner began at the District and Sessions Court in Gonda, Uttar Pradesh.

I had visited a photocopy shop within the court campus while carrying my first petition for the Allahabad High Court. The petition contained more than 180 pages, and I needed four copies. I was surprised to learn that the shop was charging ₹25 per page for scanning.

The experience made me think about the cost of handling legal paperwork and the financial realities of rural legal practice.

Conversations with senior and elderly advocates further shaped my thinking. I encountered advocates whose daily earnings could be as low as ₹500. I began considering how recurring expenses for scanning, printing, and preparing documents could affect people whose professional livelihoods were already difficult.

The legal profession has changed considerably over the years. Manual typewriters have given way to computers, digital drafting has become commonplace, and e-filing has transformed many court procedures. Yet the burden of paper remains—from school and college to employment, professional practice, and the courtroom.

I wondered whether software could help people use the A4 flatbed scanners they already had to process larger legal documents.

Within approximately 24 hours, I developed an initial prototype using VBScript to communicate with the scanner through Windows Image Acquisition (WIA). After losing a small portion of the code and encountering technical difficulties, I returned to the problem and spent almost three months developing the application.

The work evolved through experiments with C/C++ and Java, along with image-processing and OCR technologies, including ImageMagick and Tesseract.

FS Legal Size Scanner grew out of that effort to address a practical problem through software.

I do not claim that software alone can resolve the economic challenges of rural legal practice. My aim is more modest: to explore whether existing hardware can be made more useful through engineering, and whether that can help reduce one of the practical burdens associated with legal documentation.

**This project began with a problem I encountered firsthand and developed into an attempt to make legal-size scanning more accessible.**

</details>

## Contributing

Contributions, bug reports, reproducible test cases, and documentation improvements are welcome.

When reporting a problem, include:

- Windows version.
- Scanner manufacturer and model.
- Steps to reproduce the issue.
- Relevant error messages.
- Application version or release.

Please remove personal information, confidential documents, credentials, and private scanner details before sharing diagnostic files.

## Licence

FS Legal Size Scanner is published under the **GNU General Public License, version 3.0**.

See [`LICENSE.txt`](LICENSE.txt) for the complete licence text.

The project uses the `GPL-3.0-only` licensing designation. Third-party components remain subject to their own licences and terms.

## Repository and Releases

- **Source code:** [RatioJuris/FS-Legal-Size-Scanner](https://github.com/RatioJuris/FS-Legal-Size-Scanner)
- **Downloads and release notes:** [GitHub Releases](https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases)
- **Latest development target:** `v1.0.1`

Check the Releases page for the latest published version and its available installer assets.

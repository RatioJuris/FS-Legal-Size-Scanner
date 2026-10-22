# FS Legal Size Scanner

**Making legal-size document scanning more accessible with a standard A4 flatbed scanner.**

FS Legal Size Scanner is a Java-based Windows desktop application designed to help scan larger documents using a conventional A4 flatbed scanner. It processes multiple scan sections and combines them to produce a larger document.

The project was inspired by the practical cost of scanning legal documents and the challenges faced by advocates working in rural areas.

## Features

- **Legal-size scanning workflow:** Capture larger documents in sections using a compatible scanner.
- **Image processing:** Process scan images for alignment, rotation, cropping, and stitching.
- **Configurable scan settings:** Adjust parameters such as DPI, brightness, colour mode, and page dimensions.
- **Scanner integration:** Work with supported scanning backends and compatible devices.
- **Extensible source code:** Explore, modify, and improve the application under the terms of its open-source licence.

The available features depend on the application version, scanner compatibility, and installed dependencies.

## Download and Installation

FS Legal Size Scanner is intended to be distributed for Windows through a Microsoft Installer (`.msi`) package.

**Download:** [FS Legal Size Scanner — GitHub Releases](https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases)

### Installing on Windows

1. Open the [GitHub Releases page](https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases).
2. Select the release you want to install.
3. Download the `.msi` installer attached to that release.
4. Double-click the downloaded installer.
5. Follow the Windows Installer wizard to complete installation.
6. Launch FS Legal Size Scanner using the installed shortcut, if provided.

If Windows displays a security warning, verify that the installer came from the official project repository before proceeding.

> The MSI package will be made available with a published release. Runtime requirements and any additional installation steps will be documented once the installer has been finalised.

### System Requirements

The final requirements will be specified with the installer release. They may include:

- A supported version of Microsoft Windows.
- A compatible scanner and any required scanner drivers.
- A compatible Java runtime, unless bundled with the application.
- ImageMagick, Tesseract OCR, or other external components where required by the packaged workflow.

Do not assume that external dependencies are included unless the relevant release documentation confirms this.

### Uninstallation

To uninstall the application, open **Settings → Apps → Installed apps** in Windows, locate FS Legal Size Scanner, and select **Uninstall**.

Back up any documents or configuration files you want to retain before uninstalling.

## Using FS Legal Size Scanner

1. Connect the scanner and ensure that Windows recognises it.
2. Launch FS Legal Size Scanner.
3. Select the available scanner and configure the scan settings.
4. Follow the application's scanning workflow to capture the required sections of the document.
5. Allow the application to process and combine the scans.
6. Review the resulting document and save the output as required.

The exact controls and workflow may vary by application version and scanner backend. Refer to the release documentation for any version-specific instructions.

## Configuration

The application uses a local `config.properties` file for configuration and cached scanner metadata. Depending on the version, settings can include:

- Scanner identification and selection.
- Scan resolution and colour mode.
- Brightness and image-processing parameters.
- Page width and height.
- Alignment, cropping, and rotation settings.

The repository intentionally excludes the developer's local `config.properties` file because it may contain machine-specific scanner information. The application should use an appropriate local configuration for each installation.

Do not publish personal device addresses, private paths, or other sensitive local configuration values.

## Troubleshooting

### Scanner not detected

- Confirm that the scanner is powered on and connected.
- Check that Windows recognises the device.
- Verify that the necessary drivers and scanning services are installed.
- Confirm that the scanner is supported by the application's scanning backend.

### Application does not start

- Check whether the required Java runtime is installed or included with the package.
- Review the release documentation for missing dependencies.
- Confirm that the installation completed successfully.

### ImageMagick or OCR errors

If the configured workflow requires ImageMagick or Tesseract OCR, verify that the required components are installed and accessible to the application.

### Incorrect alignment or stitching

Check the scan settings, page dimensions, orientation, and alignment parameters. If the problem persists, report the steps needed to reproduce it along with relevant diagnostic information.

Before sharing logs or configuration files, remove personal information and local device addresses.

## Development

The repository contains the Java source code and application resources.

```text
FS-Legal-Size-Scanner/
├── src/          # Java source files
├── res/          # Application images and icon resources
├── run.bat       # Windows launch script
├── .gitignore
└── LICENSE
```

The local `bin/` and `build/` directories are excluded from Git. They may contain compiled or generated files and are not part of the published source tree.

### Building from Source

Development and build instructions will be documented once the required Java version, dependency versions, compilation process, and packaging workflow have been verified.

The Windows MSI installer is intended for end users; developers can use the source repository to inspect and modify the implementation.

## Third-Party Libraries and Credits

FS Legal Size Scanner has involved the use or evaluation of external tools and technologies, including ImageMagick for image processing and Tesseract OCR for optical character recognition.

A complete attribution list will be maintained here as the release dependencies are finalised.

For each dependency, the project will document:

- **Name:** The library or tool.
- **Version:** The version used in the application or installer.
- **Purpose:** How it is used by the project.
- **Official website:** The upstream project or documentation.
- **Licence:** The applicable third-party licence and attribution requirements.

Third-party components remain subject to their respective licences. The licence of this project does not replace or override those licences.

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
- The application release or version being used.

Please remove personal information, confidential documents, credentials, and private scanner details before sharing diagnostic files.

## Licence

FS Legal Size Scanner is published under the **GNU General Public License, version 3.0**.

See [`LICENSE`](LICENSE) for the complete licence text.

The project is intended to use the `GPL-3.0-only` licensing designation. Third-party components remain subject to their own licences and terms.

## Repository and Releases

- **Source code:** [RatioJuris/FS-Legal-Size-Scanner](https://github.com/RatioJuris/FS-Legal-Size-Scanner)
- **Releases and installer downloads:** [GitHub Releases](https://github.com/RatioJuris/FS-Legal-Size-Scanner/releases)
- **Current initial version:** `v1.0.0` (pre-release)


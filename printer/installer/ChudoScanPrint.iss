; «ЧудоСкан Друк» — установник без прав адміністратора і без жодних ключів.
; Збірка: iscc /DArch=x64 /DAppVersion=1.0.0 /DSourceDir=..\publish\win-x64 ChudoScanPrint.iss

#ifndef Arch
  #define Arch "x64"
#endif
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\publish\win-" + Arch
#endif

[Setup]
AppId={{6B0E7C1E-5D3A-4F7B-9C2E-1A8F0D4C2B51}
AppName=ЧудоСкан Друк
AppVersion={#AppVersion}
AppPublisher=ЧудоСкан
DefaultDirName={localappdata}\Programs\ChudoScan Print
DefaultGroupName=ЧудоСкан Друк
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=ChudoScanPrint-{#AppVersion}-{#Arch}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\ChudoScanPrint.exe
UninstallDisplayName=ЧудоСкан Друк
CloseApplications=yes
RestartApplications=no
#if Arch == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif

[Languages]
Name: "ukrainian"; MessagesFile: "compiler:Languages\Ukrainian.isl"

[Files]
Source: "{#SourceDir}\ChudoScanPrint.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\appsettings.json"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\ЧудоСкан Друк"; Filename: "{app}\ChudoScanPrint.exe"
Name: "{userdesktop}\ЧудоСкан Друк"; Filename: "{app}\ChudoScanPrint.exe"

[Registry]
; Автозапуск для поточного користувача — у трей.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "ChudoScanPrint"; ValueData: """{app}\ChudoScanPrint.exe"" --hidden"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\ChudoScanPrint.exe"; Description: "Запустити «ЧудоСкан Друк»"; Flags: nowait postinstall skipifsilent
; Самостійне оновлення (тихе встановлення) — одразу знову в трей.
Filename: "{app}\ChudoScanPrint.exe"; Parameters: "--hidden"; Flags: nowait runasoriginaluser; Check: WizardSilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM ChudoScanPrint.exe /F"; Flags: runhidden; RunOnceId: "StopApp"

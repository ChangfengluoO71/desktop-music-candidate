#define MyAppName "Desktop Music Candidate"
#ifndef MyAppVersion
#define MyAppVersion "0.0.1"
#endif
#ifndef OutputBaseFilename
#define OutputBaseFilename "DesktopMusicCandidate-" + MyAppVersion + "-Setup"
#endif
#define MyPublisher "Desktop Music Candidate"
#define MyAppExeName "DesktopMusicCandidate.exe"
#define MySourceDir "..\build\compose\binaries\main\app\DesktopMusicCandidate"

[Setup]
AppId={{E1B24D8D-F6F8-429B-9F56-7D0C37C8F2B5}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyPublisher}
DefaultDirName={autopf}\DesktopMusicCandidate
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=no
OutputDir=..\build\installer
OutputBaseFilename={#OutputBaseFilename}
Compression=lzma2/max
SolidCompression=yes
PrivilegesRequired=admin
Uninstallable=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
WizardStyle=modern
SetupIconFile=..\src\main\resources\dmc-icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional:"

[Files]
Source: "{#MySourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Code]
function InitializeSetup: Boolean;
begin
  if not IsWin64 then
  begin
    MsgBox('Desktop Music Candidate requires 64-bit Windows.', mbCriticalError, MB_OK);
    Result := False;
    Exit;
  end;
  Result := True;
end;

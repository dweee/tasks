{
  lib,
  stdenvNoCC,
  fetchurl,
  autoPatchelfHook,
  makeWrapper,
  makeDesktopItem,
  alsa-lib,
  cups,
  dbus,
  fontconfig,
  freetype,
  glib,
  gtk3,
  lcms2,
  libGL,
  libsecret,
  libx11,
  libxext,
  libxi,
  libxrender,
  libxtst,
  stdenv,
  zlib,
}: let
  system = stdenvNoCC.hostPlatform.system;
  release = builtins.fromJSON (builtins.readFile ./sources.json);
  source = release.sources.${system};
  runtimeLibraries = [
    alsa-lib cups dbus fontconfig freetype glib gtk3 lcms2 libGL libsecret
    libx11 libxext libxi libxrender libxtst stdenv.cc.cc.lib zlib
  ];
  desktopItem = makeDesktopItem {
    name = "tasks-org";
    desktopName = "Tasks.org";
    comment = "Tasks, reminders and CalDAV sync";
    exec = "tasks-org %U";
    icon = "tasks-org";
    categories = ["Office" "ProjectManagement"];
    terminal = false;
  };
in stdenvNoCC.mkDerivation {
  pname = "tasks-org";
  inherit (release) version;
  src = fetchurl {
    inherit (source) url hash;
    name = "tasks-org-${system}.tar.gz";
  };
  sourceRoot = ".";
  nativeBuildInputs = [makeWrapper] ++ lib.optional stdenvNoCC.hostPlatform.isLinux autoPatchelfHook;
  buildInputs = lib.optionals stdenvNoCC.hostPlatform.isLinux runtimeLibraries;
  dontStrip = true;
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p "$out/bin"
    ${lib.optionalString stdenvNoCC.hostPlatform.isLinux ''
      mkdir -p "$out/lib"
      cp -R tasks-org "$out/lib/tasks-org"
      makeWrapper "$out/lib/tasks-org/bin/tasks-org" "$out/bin/tasks-org" \
        --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath runtimeLibraries}
      mkdir -p "$out/share/applications" "$out/share/icons/hicolor/scalable/apps"
      cp ${desktopItem}/share/applications/*.desktop "$out/share/applications/"
      cp ${../graphics/icon.svg} "$out/share/icons/hicolor/scalable/apps/tasks-org.svg"
    ''}
    ${lib.optionalString stdenvNoCC.hostPlatform.isDarwin ''
      mkdir -p "$out/Applications"
      cp -R tasks-org.app "$out/Applications/"
      makeWrapper "$out/Applications/tasks-org.app/Contents/MacOS/tasks-org" "$out/bin/tasks-org"
      /usr/bin/codesign --verify --strict --deep "$out/Applications/tasks-org.app"
    ''}
    runHook postInstall
  '';
  passthru.sourceRevision = release.revision;
  meta = {
    description = "Tasks.org desktop client with CalDAV synchronization";
    homepage = "https://github.com/dweee/tasks";
    license = lib.licenses.gpl3Only;
    mainProgram = "tasks-org";
    platforms = ["x86_64-linux" "aarch64-darwin"];
    sourceProvenance = [lib.sourceTypes.binaryNativeCode lib.sourceTypes.binaryBytecode];
  };
}

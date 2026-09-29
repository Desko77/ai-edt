param(
    [Parameter(Mandatory = $true)][string]$Pids,
    [Parameter(Mandatory = $false)][string]$ImageDir = '',
    [Parameter(Mandatory = $true)][string]$ResultPath
)

# Reads the 1C windows that are holding the named processes: title, message texts, buttons,
# whether the window is the enabled dialog over a disabled owner, and a PNG of the window.
# An empty ImageDir reports the windows without a picture: the directory is only written into
# when the caller named one.
# The document is written to ResultPath as UTF-8. Stdout is not used: a console code page
# would turn Cyrillic titles into a different string on the way out.

$ErrorActionPreference = 'Stop'

function Escape-JsonText([string]$value) {
    if ($null -eq $value) { return '' }
    $builder = New-Object System.Text.StringBuilder
    foreach ($char in $value.ToCharArray()) {
        $code = [int]$char
        if ($code -eq 34) { [void]$builder.Append('\"') }
        elseif ($code -eq 92) { [void]$builder.Append('\\') }
        elseif ($code -eq 8) { [void]$builder.Append('\b') }
        elseif ($code -eq 9) { [void]$builder.Append('\t') }
        elseif ($code -eq 10) { [void]$builder.Append('\n') }
        elseif ($code -eq 13) { [void]$builder.Append('\r') }
        elseif ($code -lt 32) { [void]$builder.AppendFormat('\u{0:x4}', $code) }
        else { [void]$builder.Append($char) }
    }
    return $builder.ToString()
}

function Format-JsonString([string]$value) {
    return '"' + (Escape-JsonText $value) + '"'
}

function Format-JsonStringList($items) {
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($item in $items) {
        $parts.Add((Format-JsonString ([string]$item)))
    }
    if ($parts.Count -eq 0) { return '[]' }
    return '[' + ([string]::Join(',', $parts.ToArray())) + ']'
}

function Write-Result([string]$json) {
    $directory = Split-Path -Parent $ResultPath
    if ($directory -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    [System.IO.File]::WriteAllText($ResultPath, $json, [System.Text.Encoding]::UTF8)
}

try {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    Add-Type -AssemblyName System.Drawing
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class AiedtClientDialogNative {
    public delegate bool EnumProc(IntPtr hwnd, IntPtr lparam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc callback, IntPtr lparam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int capacity);
    [DllImport("user32.dll")] public static extern int GetClassName(IntPtr hwnd, StringBuilder text, int capacity);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr hwnd, uint command);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    public const uint GW_OWNER = 4;
}
"@

    if (-not [string]::IsNullOrWhiteSpace($ImageDir) -and -not (Test-Path -LiteralPath $ImageDir)) {
        New-Item -ItemType Directory -Force -Path $ImageDir | Out-Null
    }

    $script:wanted = New-Object 'System.Collections.Generic.HashSet[int64]'
    foreach ($part in $Pids.Split(',')) {
        $trimmed = $part.Trim()
        if ($trimmed.Length -eq 0) { continue }
        $parsed = 0L
        if ([int64]::TryParse($trimmed, [ref]$parsed) -and $parsed -gt 0) {
            [void]$script:wanted.Add($parsed)
        }
    }

    $script:handles = New-Object 'System.Collections.Generic.List[IntPtr]'
    $enum = {
        param($hwnd, $param)
        $processId = 0
        [void][AiedtClientDialogNative]::GetWindowThreadProcessId($hwnd, [ref]$processId)
        if ($script:wanted.Contains([int64]$processId) -and [AiedtClientDialogNative]::IsWindowVisible($hwnd)) {
            $script:handles.Add($hwnd)
        }
        return $true
    }
    [void][AiedtClientDialogNative]::EnumWindows($enum, [IntPtr]::Zero)

    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $textTypeId = [System.Windows.Automation.ControlType]::Text.Id
    $paneTypeId = [System.Windows.Automation.ControlType]::Pane.Id
    $buttonTypeId = [System.Windows.Automation.ControlType]::Button.Id
    $windowTypeId = [System.Windows.Automation.ControlType]::Window.Id
    $fragments = New-Object System.Collections.Generic.List[string]
    $index = 0

    foreach ($hwnd in $script:handles) {
        $titleBuilder = New-Object System.Text.StringBuilder 2048
        [void][AiedtClientDialogNative]::GetWindowText($hwnd, $titleBuilder, $titleBuilder.Capacity)
        $classBuilder = New-Object System.Text.StringBuilder 256
        [void][AiedtClientDialogNative]::GetClassName($hwnd, $classBuilder, $classBuilder.Capacity)
        $title = $titleBuilder.ToString()
        $className = $classBuilder.ToString()
        $titleBlank = [string]::IsNullOrWhiteSpace($title)
        if (($className -eq 'V8Window' -and $titleBlank) -or $className -eq 'V8NotificationWindow') {
            continue
        }

        $processId = 0
        [void][AiedtClientDialogNative]::GetWindowThreadProcessId($hwnd, [ref]$processId)
        $enabled = [AiedtClientDialogNative]::IsWindowEnabled($hwnd)
        $owner = [AiedtClientDialogNative]::GetWindow($hwnd, [AiedtClientDialogNative]::GW_OWNER)
        $ownerDisabled = ($owner.ToInt64() -ne 0) -and -not [AiedtClientDialogNative]::IsWindowEnabled($owner)
        $modal = $enabled -and $ownerDisabled
        if (-not $modal) { continue }

        $texts = New-Object System.Collections.Generic.List[string]
        $seenTexts = New-Object 'System.Collections.Generic.HashSet[string]'
        $buttons = New-Object System.Collections.Generic.List[string]
        try {
            $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
            $seen = 0
            $stack = New-Object System.Collections.Generic.Stack[object]
            $firstChild = $walker.GetFirstChild($root)
            if ($null -ne $firstChild) { $stack.Push($firstChild) }
            while ($stack.Count -gt 0 -and $seen -lt 400) {
                $element = $stack.Pop()
                $seen++
                $sibling = $walker.GetNextSibling($element)
                if ($null -ne $sibling) { $stack.Push($sibling) }
                try {
                    $typeId = $element.Current.ControlType.Id
                    if ($typeId -eq $windowTypeId) { continue }
                    $name = $element.Current.Name
                    if (($typeId -eq $textTypeId -or $typeId -eq $paneTypeId) -and -not [string]::IsNullOrWhiteSpace($name)) {
                        # A 1C question box reports its message as the name of a pane, and a pane
                        # that only repeats the frame title is not a message line. One line is
                        # listed once, in the order the walk reaches it.
                        $isTitle = [string]::Equals($name.Trim(), $title.Trim(), [System.StringComparison]::Ordinal)
                        if (-not $isTitle -and $seenTexts.Add($name)) {
                            $texts.Add($name)
                        }
                    } elseif ($typeId -eq $buttonTypeId -and -not [string]::IsNullOrWhiteSpace($name)) {
                        $buttons.Add($name)
                    }
                    $deeper = $walker.GetFirstChild($element)
                    if ($null -ne $deeper) { $stack.Push($deeper) }
                } catch {
                    # A stale element is skipped. The rest of the dialog is still worth reading.
                }
            }
        } catch {
            # The window went away between the enumeration and the walk. Title and buttons
            # that were not read stay empty; the frame itself is still reported.
        }

        $imageFile = ''
        try {
            if (-not [string]::IsNullOrWhiteSpace($ImageDir)) {
                $rect = New-Object AiedtClientDialogNative+RECT
                $gotRect = [AiedtClientDialogNative]::GetWindowRect($hwnd, [ref]$rect)
                $width = $rect.Right - $rect.Left
                $height = $rect.Bottom - $rect.Top
                if ($gotRect -and $width -gt 0 -and $height -gt 0) {
                    $bitmap = New-Object System.Drawing.Bitmap $width, $height
                    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                    try {
                        $deviceContext = $graphics.GetHdc()
                        try {
                            # PrintWindow draws the window itself, so an application covering it
                            # does not end up in the picture. Flag 2 is PW_RENDERFULLCONTENT.
                            $drawn = [AiedtClientDialogNative]::PrintWindow($hwnd, $deviceContext, 2)
                        } finally {
                            $graphics.ReleaseHdc($deviceContext)
                        }
                        if ($drawn) {
                            $imageFile = [System.IO.Path]::GetFullPath((Join-Path $ImageDir ("blocking-{0}-{1}.png" -f $processId, $index)))
                            $bitmap.Save($imageFile, [System.Drawing.Imaging.ImageFormat]::Png)
                        }
                    } finally {
                        $graphics.Dispose()
                        $bitmap.Dispose()
                    }
                }
            }
        } catch {
            # A window that cannot be drawn is still reported, without a picture.
            $imageFile = ''
        }

        $modalJson = 'false'
        if ($modal) { $modalJson = 'true' }
        $fragment = '{"pid":' + $processId `
            + ',"className":' + (Format-JsonString $className) `
            + ',"title":' + (Format-JsonString $title) `
            + ',"texts":' + (Format-JsonStringList $texts) `
            + ',"buttons":' + (Format-JsonStringList $buttons) `
            + ',"modal":' + $modalJson `
            + ',"imageFile":' + (Format-JsonString $imageFile) + '}'
        $fragments.Add($fragment)
        $index++
    }

    Write-Result ('{"windows":[' + ([string]::Join(',', $fragments.ToArray())) + ']}')
    exit 0
} catch {
    $reason = $_.Exception.Message
    if ([string]::IsNullOrWhiteSpace($reason)) { $reason = "$_" }
    try {
        Write-Result ('{"windows":[],"error":' + (Format-JsonString $reason) + '}')
    } catch {
        [System.IO.File]::WriteAllText($ResultPath, '{"windows":[],"error":"The dialog reader failed."}', [System.Text.Encoding]::UTF8)
    }
    exit 1
}

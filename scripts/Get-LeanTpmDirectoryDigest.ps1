[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DirectoryPath,
    [ValidateSet('Text', 'Json')][string]$OutputFormat = 'Text'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $DirectoryPath).Path.TrimEnd('\', '/')
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw 'DirectoryPath must resolve to a directory'
}

if (-not ('LeanTpm.DirectoryDigestV1' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace LeanTpm {
    public sealed class DirectoryDigestResult {
        public string Digest;
        public int FileCount;
        public long TotalBytes;
    }

    public static class DirectoryDigestV1 {
        private static void AddBytes(HashAlgorithm hash, byte[] bytes, int count) {
            if (count > 0) {
                hash.TransformBlock(bytes, 0, count, bytes, 0);
            }
        }

        public static DirectoryDigestResult Compute(string root) {
            root = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var pending = new Queue<string>();
            var files = new List<string>();
            pending.Enqueue(root);
            while (pending.Count > 0) {
                string directory = pending.Dequeue();
                foreach (string child in Directory.GetDirectories(directory)) {
                    if ((File.GetAttributes(child) & FileAttributes.ReparsePoint) != 0) {
                        throw new InvalidOperationException("Directory digest does not follow reparse points: " + child);
                    }
                    pending.Enqueue(child);
                }
                foreach (string file in Directory.GetFiles(directory)) {
                    if ((File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0) {
                        throw new InvalidOperationException("Directory digest does not follow reparse points: " + file);
                    }
                    files.Add(file);
                }
            }
            files.Sort(StringComparer.Ordinal);
            var encoding = new UTF8Encoding(false);
            var separator = new byte[] { 0 };
            var buffer = new byte[1024 * 1024];
            long totalBytes = 0;
            using (var hash = SHA256.Create()) {
                foreach (string filePath in files) {
                    string relativePath = filePath.Substring(root.Length + 1).Replace('\\', '/');
                    byte[] pathBytes = encoding.GetBytes(relativePath);
                    AddBytes(hash, pathBytes, pathBytes.Length);
                    AddBytes(hash, separator, separator.Length);
                    var info = new FileInfo(filePath);
                    byte[] lengthBytes = encoding.GetBytes(info.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
                    AddBytes(hash, lengthBytes, lengthBytes.Length);
                    AddBytes(hash, separator, separator.Length);
                    using (var stream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, buffer.Length, FileOptions.SequentialScan)) {
                        int read;
                        while ((read = stream.Read(buffer, 0, buffer.Length)) > 0) {
                            AddBytes(hash, buffer, read);
                        }
                    }
                    AddBytes(hash, separator, separator.Length);
                    totalBytes += info.Length;
                }
                hash.TransformFinalBlock(new byte[0], 0, 0);
                return new DirectoryDigestResult {
                    Digest = BitConverter.ToString(hash.Hash).Replace("-", "").ToLowerInvariant(),
                    FileCount = files.Count,
                    TotalBytes = totalBytes
                };
            }
        }
    }
}
'@
}

$computed = [LeanTpm.DirectoryDigestV1]::Compute($root)
$report = [pscustomobject]@{
    status = 'PASS'
    algorithm = 'LEANTPM-DIRECTORY-SHA256-V1'
    digest = $computed.Digest
    fileCount = $computed.FileCount
    totalBytes = $computed.TotalBytes
}
if ($OutputFormat -eq 'Json') { $report | ConvertTo-Json -Compress }
else { $report | Format-List }

# unsafe-password-storage-check

**Category:** Metadata  ·  **Severity:** Critical (Vulnerability)

Flags metadata attributes that look like they hold a plaintext credential directly - names like `Password`, `SecretKey`, `APIKey` on a catalog, register or other object.

## Why it matters
Anything stored in an ordinary attribute is as readable as any other business data: to database administrators, to anyone with query access, in backups, and potentially through a SQL injection. None of the platform's usual data-protection layers were designed with secrets in mind, so a `Password` attribute effectively publishes the credential to everyone who can read that table.

## How to fix
Don't model a secret as a plain attribute. Use the platform's secure storage API for credentials and tokens, prefer platform/OS/OAuth authentication over rolling your own password storage, and if a password-derived value truly must persist, store a salted hash - never the password itself.

## Example
```
Catalog: Integrations
└── Attributes
    └── APIKey: String   -- stored as plain data, readable by anyone with access
```
Move the secret into secure storage instead of a metadata attribute:
```bsl
SecureDataStorage.Write(StorageKey, ApiKeyValue);
StoredKey = SecureDataStorage.Read(StorageKey);
```

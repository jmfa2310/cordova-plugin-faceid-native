#import "SecureProtectionStoreIOS.h"
#import <Security/Security.h>

static NSString * const kFaceIDService = @"com.company.faceidnative.protection.v2";
static NSString * const kFaceIDMetaService = @"com.company.faceidnative.protection.meta.v2";
static NSString * const kFaceIDActiveAccount = @"active_scope";

@implementation FaceIDSavedProtection
@end

@implementation SecureProtectionStoreIOS

+ (NSString *)normalizeScope:(NSString *)scope {
    NSString *trimmed = [[scope ?: @"" stringByTrimmingCharactersInSet:
                          [NSCharacterSet whitespaceAndNewlineCharacterSet]] copy];
    return trimmed.length > 0 ? trimmed : @"default";
}

+ (NSMutableDictionary *)queryForService:(NSString *)service account:(NSString *)account {
    return [@{
        (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: service,
        (__bridge id)kSecAttrAccount: account
    } mutableCopy];
}

+ (BOOL)putData:(NSData *)data
        service:(NSString *)service
        account:(NSString *)account
          error:(NSError **)error {

    NSMutableDictionary *query = [self queryForService:service account:account];
    SecItemDelete((__bridge CFDictionaryRef)query);

    query[(__bridge id)kSecValueData] = data;
    query[(__bridge id)kSecAttrAccessible] =
            (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;

    OSStatus status = SecItemAdd((__bridge CFDictionaryRef)query, NULL);

    if (status != errSecSuccess) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Keychain"
                                         code:status
                                     userInfo:@{
                NSLocalizedDescriptionKey:
                    [NSString stringWithFormat:@"Keychain write failed (%d).", (int)status]
            }];
        }
        return NO;
    }

    return YES;
}

+ (nullable NSData *)getDataForService:(NSString *)service
                               account:(NSString *)account
                                 error:(NSError **)error {

    NSMutableDictionary *query = [self queryForService:service account:account];
    query[(__bridge id)kSecReturnData] = @YES;
    query[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;

    CFTypeRef out = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &out);

    if (status == errSecItemNotFound) {
        return nil;
    }

    if (status != errSecSuccess) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Keychain"
                                         code:status
                                     userInfo:@{
                NSLocalizedDescriptionKey:
                    [NSString stringWithFormat:@"Keychain read failed (%d).", (int)status]
            }];
        }
        return nil;
    }

    return CFBridgingRelease(out);
}

+ (BOOL)saveMasterKey:(NSData *)masterKey
                scope:(NSString *)scope
              version:(NSInteger)version
                error:(NSError **)error {

    if (masterKey.length < 32 || version < 1) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Keychain"
                                         code:-1
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Invalid protection configuration."
            }];
        }
        return NO;
    }

    NSString *normalized = [self normalizeScope:scope];

    uint32_t v = CFSwapInt32HostToBig((uint32_t)version);
    NSMutableData *payload = [NSMutableData dataWithBytes:&v length:sizeof(v)];
    [payload appendData:masterKey];

    if (![self putData:payload service:kFaceIDService account:normalized error:error]) {
        return NO;
    }

    NSData *scopeData = [normalized dataUsingEncoding:NSUTF8StringEncoding];

    if (![self putData:scopeData
               service:kFaceIDMetaService
               account:kFaceIDActiveAccount
                 error:error]) {
        return NO;
    }

    return YES;
}

+ (nullable FaceIDSavedProtection *)loadScope:(NSString *)scope
                                        error:(NSError **)error {

    NSString *normalized = [self normalizeScope:scope];
    NSData *payload = [self getDataForService:kFaceIDService
                                      account:normalized
                                        error:error];

    if (!payload || payload.length < 36) {
        return nil;
    }

    uint32_t versionBE = 0;
    [payload getBytes:&versionBE length:sizeof(versionBE)];
    NSInteger version = (NSInteger)CFSwapInt32BigToHost(versionBE);

    NSData *masterKey = [payload subdataWithRange:NSMakeRange(4, payload.length - 4)];

    if (version < 1 || masterKey.length < 32) {
        return nil;
    }

    FaceIDSavedProtection *saved = [FaceIDSavedProtection new];
    saved.masterKey = masterKey;
    saved.version = version;
    saved.scope = normalized;
    return saved;
}

+ (nullable FaceIDSavedProtection *)loadActive:(NSError **)error {
    NSString *scope = [self activeScope];
    if (!scope) return nil;
    return [self loadScope:scope error:error];
}

+ (BOOL)activateScope:(NSString *)scope error:(NSError **)error {
    NSString *normalized = [self normalizeScope:scope];

    if (![self existsScope:normalized]) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Keychain"
                                         code:-2
                                     userInfo:@{
                NSLocalizedDescriptionKey:
                    [NSString stringWithFormat:@"Protection scope not provisioned: %@", normalized]
            }];
        }
        return NO;
    }

    NSData *scopeData = [normalized dataUsingEncoding:NSUTF8StringEncoding];

    return [self putData:scopeData
                 service:kFaceIDMetaService
                 account:kFaceIDActiveAccount
                   error:error];
}

+ (nullable NSString *)activeScope {
    NSData *data = [self getDataForService:kFaceIDMetaService
                                   account:kFaceIDActiveAccount
                                     error:nil];
    if (!data) return nil;
    NSString *scope = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    return scope.length > 0 ? scope : nil;
}

+ (BOOL)existsScope:(NSString *)scope {
    return [self getDataForService:kFaceIDService
                           account:[self normalizeScope:scope]
                             error:nil] != nil;
}

+ (BOOL)existsActive {
    NSString *scope = [self activeScope];
    return scope != nil && [self existsScope:scope];
}

+ (void)deleteService:(NSString *)service account:(NSString *)account {
    NSMutableDictionary *query = [self queryForService:service account:account];
    SecItemDelete((__bridge CFDictionaryRef)query);
}

+ (void)clearScope:(NSString *)scope {
    NSString *normalized = [self normalizeScope:scope];
    [self deleteService:kFaceIDService account:normalized];

    NSString *active = [self activeScope];
    if ([active isEqualToString:normalized]) {
        [self deleteService:kFaceIDMetaService account:kFaceIDActiveAccount];
    }
}

+ (void)clearAll {
    NSDictionary *q1 = @{
        (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: kFaceIDService
    };
    SecItemDelete((__bridge CFDictionaryRef)q1);

    NSDictionary *q2 = @{
        (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: kFaceIDMetaService
    };
    SecItemDelete((__bridge CFDictionaryRef)q2);
}

@end

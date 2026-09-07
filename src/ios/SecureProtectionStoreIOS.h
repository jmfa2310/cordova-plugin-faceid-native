#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface FaceIDSavedProtection : NSObject
@property(nonatomic, strong) NSData *masterKey;
@property(nonatomic) NSInteger version;
@property(nonatomic, copy) NSString *scope;
@end

@interface SecureProtectionStoreIOS : NSObject
+ (BOOL)saveMasterKey:(NSData *)masterKey
                scope:(NSString *)scope
              version:(NSInteger)version
                error:(NSError **)error;

+ (nullable FaceIDSavedProtection *)loadScope:(NSString *)scope
                                        error:(NSError **)error;

+ (nullable FaceIDSavedProtection *)loadActive:(NSError **)error;

+ (BOOL)activateScope:(NSString *)scope error:(NSError **)error;

+ (nullable NSString *)activeScope;

+ (BOOL)existsScope:(NSString *)scope;
+ (BOOL)existsActive;

+ (void)clearScope:(NSString *)scope;
+ (void)clearAll;
@end

NS_ASSUME_NONNULL_END

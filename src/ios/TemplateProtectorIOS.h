#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface TemplateProtectorIOS : NSObject

@property(nonatomic, readonly) NSInteger version;
@property(nonatomic, copy, readonly) NSString *scope;
@property(nonatomic, copy, readonly) NSString *keyId;

+ (NSString *)scheme;
+ (NSInteger)templateBits;
+ (NSString *)normalizeScope:(NSString *)scope;

+ (nullable NSData *)deriveMasterKeyFromText:(NSString *)protectionKeyText
                                      scope:(NSString *)scope
                                      error:(NSError **)error;

- (nullable instancetype)initWithMasterKey:(NSData *)masterKey
                                   version:(NSInteger)version
                                     scope:(NSString *)scope
                                     error:(NSError **)error;

- (nullable NSData *)protectEmbedding:(const float *)embedding
                                count:(NSUInteger)count
                                error:(NSError **)error;

- (NSString *)encodeCode:(NSData *)code;

- (nullable NSData *)decodeTemplate:(NSString *)protectedTemplate;

- (double)similarityBetween:(NSData *)a and:(NSData *)b;

- (void)wipe;

@end

NS_ASSUME_NONNULL_END

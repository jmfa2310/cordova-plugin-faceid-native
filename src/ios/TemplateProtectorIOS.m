#import "TemplateProtectorIOS.h"
#import <CommonCrypto/CommonDigest.h>
#import <CommonCrypto/CommonHMAC.h>
#import <math.h>

static const NSInteger kTemplateBits = 256;
static const NSInteger kTemplateBytes = 32;
static const NSInteger kInputDim = 128;

@interface TemplateProtectorIOS ()
@property(nonatomic) NSInteger version;
@property(nonatomic, copy) NSString *scope;
@property(nonatomic, copy) NSString *keyId;
@property(nonatomic, strong) NSMutableData *projection;
@end

@implementation TemplateProtectorIOS

+ (NSString *)scheme {
    return @"PT2_KEYED_RP_256_SCOPE_BOUND";
}

+ (NSInteger)templateBits {
    return kTemplateBits;
}

+ (NSString *)normalizeScope:(NSString *)scope {
    NSString *trimmed = [scope ?: @"" stringByTrimmingCharactersInSet:
                         [NSCharacterSet whitespaceAndNewlineCharacterSet]];
    return trimmed.length > 0 ? trimmed : @"default";
}

+ (NSData *)sha256ForData:(NSData *)data {
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
    return [NSData dataWithBytes:digest length:sizeof(digest)];
}

+ (nullable NSData *)deriveMasterKeyFromText:(NSString *)protectionKeyText
                                      scope:(NSString *)scope
                                      error:(NSError **)error {

    NSString *trimmed = [protectionKeyText ?: @"" stringByTrimmingCharactersInSet:
                         [NSCharacterSet whitespaceAndNewlineCharacterSet]];

    if (trimmed.length < 32) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Protection"
                                         code:-1
                                     userInfo:@{
                NSLocalizedDescriptionKey:
                    @"ProtectionKey must be a random secret with at least 32 characters."
            }];
        }
        return nil;
    }

    NSString *normalized = [self normalizeScope:scope];
    NSMutableData *data = [NSMutableData data];

    [data appendData:[@"FaceIDNative|PT2|master|" dataUsingEncoding:NSUTF8StringEncoding]];
    [data appendData:[normalized dataUsingEncoding:NSUTF8StringEncoding]];

    uint8_t separator = '|';
    [data appendBytes:&separator length:1];

    [data appendData:[trimmed dataUsingEncoding:NSUTF8StringEncoding]];
    return [self sha256ForData:data];
}

+ (NSString *)base64URLNoPadding:(NSData *)data {
    NSString *s = [data base64EncodedStringWithOptions:0];
    s = [s stringByReplacingOccurrencesOfString:@"+" withString:@"-"];
    s = [s stringByReplacingOccurrencesOfString:@"/" withString:@"_"];
    while ([s hasSuffix:@"="]) {
        s = [s substringToIndex:s.length - 1];
    }
    return s;
}

+ (nullable NSData *)decodeBase64URLNoPadding:(NSString *)text {
    NSString *s = [text stringByReplacingOccurrencesOfString:@"-" withString:@"+"];
    s = [s stringByReplacingOccurrencesOfString:@"_" withString:@"/"];

    NSUInteger remainder = s.length % 4;
    if (remainder != 0) {
        s = [s stringByPaddingToLength:s.length + (4 - remainder)
                             withString:@"="
                        startingAtIndex:0];
    }

    return [[NSData alloc] initWithBase64EncodedString:s options:0];
}

- (nullable instancetype)initWithMasterKey:(NSData *)masterKey
                                   version:(NSInteger)version
                                     scope:(NSString *)scope
                                     error:(NSError **)error {

    if (masterKey.length < 32 || version < 1) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Protection"
                                         code:-2
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Invalid protection configuration."
            }];
        }
        return nil;
    }

    self = [super init];
    if (!self) return nil;

    _version = version;
    _scope = [[self class] normalizeScope:scope];

    NSMutableData *keyIdInput = [NSMutableData dataWithData:
        [@"FaceIDNative|PT2|keyid" dataUsingEncoding:NSUTF8StringEncoding]];
    [keyIdInput appendData:masterKey];

    NSData *keyIdDigest = [[self class] sha256ForData:keyIdInput];
    _keyId = [[self class] base64URLNoPadding:
              [keyIdDigest subdataWithRange:NSMakeRange(0, 8)]];

    _projection = [NSMutableData dataWithLength:kTemplateBits * kInputDim];

    uint8_t *matrix = _projection.mutableBytes;
    NSData *domain = [@"FaceIDNative|PT2|projection"
                      dataUsingEncoding:NSUTF8StringEncoding];

    for (int bit = 0; bit < kTemplateBits; bit++) {
        int dim = 0;
        int block = 0;

        while (dim < kInputDim) {
            NSMutableData *message = [NSMutableData dataWithData:domain];

            uint32_t v = CFSwapInt32HostToBig((uint32_t)version);
            uint32_t b = CFSwapInt32HostToBig((uint32_t)bit);
            uint32_t k = CFSwapInt32HostToBig((uint32_t)block++);

            [message appendBytes:&v length:4];
            [message appendBytes:&b length:4];
            [message appendBytes:&k length:4];

            unsigned char digest[CC_SHA256_DIGEST_LENGTH];
            CCHmac(kCCHmacAlgSHA256,
                   masterKey.bytes,
                   masterKey.length,
                   message.bytes,
                   message.length,
                   digest);

            for (int i = 0; i < CC_SHA256_DIGEST_LENGTH && dim < kInputDim; i++, dim++) {
                matrix[bit * kInputDim + dim] =
                        ((digest[i] & 0x01) == 0) ? (uint8_t)0xFF : (uint8_t)0x01;
            }

            memset(digest, 0, sizeof(digest));
        }
    }

    return self;
}

- (nullable NSData *)protectEmbedding:(const float *)embedding
                                count:(NSUInteger)count
                                error:(NSError **)error {

    if (!embedding || count != kInputDim) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Protection"
                                         code:-3
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Unexpected FaceNet embedding size."
            }];
        }
        return nil;
    }

    uint8_t code[kTemplateBytes];
    memset(code, 0, sizeof(code));

    const uint8_t *matrix = self.projection.bytes;

    for (int bit = 0; bit < kTemplateBits; bit++) {
        double dot = 0.0;

        for (int d = 0; d < kInputDim; d++) {
            int8_t signValue = (int8_t)matrix[bit * kInputDim + d];
            dot += embedding[d] * signValue;
        }

        if (dot >= 0.0) {
            code[bit >> 3] |= (uint8_t)(1 << (bit & 7));
        }
    }

    return [NSData dataWithBytes:code length:sizeof(code)];
}

- (NSString *)encodeCode:(NSData *)code {
    if (code.length != kTemplateBytes) return @"";

    return [NSString stringWithFormat:@"PT2:%ld:%@:%@",
            (long)self.version,
            self.keyId,
            [[self class] base64URLNoPadding:code]];
}

- (nullable NSData *)decodeTemplate:(NSString *)protectedTemplate {
    NSArray<NSString *> *parts = [[protectedTemplate ?: @"" stringByTrimmingCharactersInSet:
                                  [NSCharacterSet whitespaceAndNewlineCharacterSet]]
                                  componentsSeparatedByString:@":"];

    if (parts.count != 4 || ![parts[0] isEqualToString:@"PT2"]) {
        return nil;
    }

    NSInteger incomingVersion = [parts[1] integerValue];

    if (incomingVersion != self.version ||
        ![parts[2] isEqualToString:self.keyId]) {
        return nil;
    }

    NSData *data = [[self class] decodeBase64URLNoPadding:parts[3]];
    return data.length == kTemplateBytes ? data : nil;
}

- (double)similarityBetween:(NSData *)a and:(NSData *)b {
    if (a.length != kTemplateBytes || b.length != kTemplateBytes) {
        return -1.0;
    }

    const uint8_t *aa = a.bytes;
    const uint8_t *bb = b.bytes;
    int different = 0;

    for (int i = 0; i < kTemplateBytes; i++) {
        uint8_t x = aa[i] ^ bb[i];
        different += __builtin_popcount((unsigned int)x);
    }

    double agreement = 1.0 - (different / (double)kTemplateBits);
    double estimatedCosine = cos(M_PI * (1.0 - agreement));

    if (estimatedCosine < -1.0) estimatedCosine = -1.0;
    if (estimatedCosine > 1.0) estimatedCosine = 1.0;

    return estimatedCosine;
}

- (void)wipe {
    if (self.projection.length > 0) {
        memset(self.projection.mutableBytes, 0, self.projection.length);
    }
}

- (void)dealloc {
    [self wipe];
}

@end

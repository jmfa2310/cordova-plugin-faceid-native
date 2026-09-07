#import "FaceIDPlugin.h"
#import "FaceNetEngineIOS.h"
#import "TemplateProtectorIOS.h"
#import "SecureProtectionStoreIOS.h"

#import <Vision/Vision.h>
#import <AVFoundation/AVFoundation.h>
#import <ImageIO/ImageIO.h>
#import <math.h>

static const double kDefaultThreshold = 0.55;
static const double kDefaultMinGap = 0.03;

@interface FaceIDEmployeeTemplate : NSObject
@property(nonatomic, copy) NSString *employeeId;
@property(nonatomic, copy) NSString *name;
@property(nonatomic, strong) NSData *protectedTemplate;
@end

@implementation FaceIDEmployeeTemplate
@end

@interface FaceIDPlugin ()
@property(nonatomic, strong) FaceNetEngineIOS *engine;
@property(nonatomic, strong) TemplateProtectorIOS *protector;
@property(nonatomic, copy) NSArray<FaceIDEmployeeTemplate *> *employees;

@property(nonatomic, copy) NSString *pendingCaptureCallbackId;
@property(nonatomic) double pendingThreshold;
@property(nonatomic) double pendingMinGap;
@property(nonatomic) BOOL pendingCaptureTemplate;
@property(nonatomic, strong) UIImagePickerController *picker;
@end

@implementation FaceIDPlugin

- (void)pluginInitialize {
    self.employees = @[];
}

#pragma mark - Cordova helpers

- (void)sendOK:(NSDictionary *)payload callbackId:(NSString *)callbackId {
    CDVPluginResult *result =
            [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                         messageAsDictionary:payload ?: @{}];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
}

- (void)sendError:(NSString *)message callbackId:(NSString *)callbackId {
    CDVPluginResult *result =
            [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                              messageAsString:message ?: @"FaceID error."];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
}

- (NSString *)messageWithPrefix:(NSString *)prefix error:(NSError *)error {
    NSString *detail = error.localizedDescription ?: @"Unknown error.";
    return [NSString stringWithFormat:@"%@: %@", prefix, detail];
}

- (NSString *)argString:(NSArray *)args index:(NSUInteger)index fallback:(NSString *)fallback {
    if (index >= args.count) return fallback;
    id value = args[index];
    if (!value || value == [NSNull null]) return fallback;
    return [NSString stringWithFormat:@"%@", value];
}

- (NSInteger)argInteger:(NSArray *)args index:(NSUInteger)index fallback:(NSInteger)fallback {
    if (index >= args.count) return fallback;
    id value = args[index];
    if ([value respondsToSelector:@selector(integerValue)]) {
        return [value integerValue];
    }
    return fallback;
}

- (double)argDouble:(NSArray *)args index:(NSUInteger)index fallback:(double)fallback {
    if (index >= args.count) return fallback;
    id value = args[index];
    if ([value respondsToSelector:@selector(doubleValue)]) {
        return [value doubleValue];
    }
    return fallback;
}

#pragma mark - Protection

- (BOOL)ensureProtector:(NSError **)error {
    if (self.protector) return YES;

    FaceIDSavedProtection *saved =
            [SecureProtectionStoreIOS loadActive:error];

    if (!saved || saved.masterKey.length < 32) {
        if (error && !*error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Protection"
                                         code:-10
                                     userInfo:@{
                NSLocalizedDescriptionKey:
                    @"PROTECTION_NOT_CONFIGURED: provision the company key once online or activate a stored company scope."
            }];
        }
        return NO;
    }

    TemplateProtectorIOS *protector =
            [[TemplateProtectorIOS alloc] initWithMasterKey:saved.masterKey
                                                   version:saved.version
                                                     scope:saved.scope
                                                     error:error];

    if (!protector) return NO;

    self.protector = protector;
    return YES;
}

- (void)wipeEmployees {
    self.employees = @[];
}

- (void)setProtectionKey:(CDVInvokedUrlCommand *)command {
    [self.commandDelegate runInBackground:^{
        NSString *key = [self argString:command.arguments index:0 fallback:@""];
        NSInteger version = MAX(1, [self argInteger:command.arguments index:1 fallback:1]);
        NSString *scope =
                [TemplateProtectorIOS normalizeScope:
                 [self argString:command.arguments index:2 fallback:@"default"]];

        NSError *error = nil;
        NSData *masterKey =
                [TemplateProtectorIOS deriveMasterKeyFromText:key
                                                       scope:scope
                                                       error:&error];

        if (!masterKey) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_SETUP_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        if (![SecureProtectionStoreIOS saveMasterKey:masterKey
                                              scope:scope
                                            version:version
                                              error:&error]) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_SETUP_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        TemplateProtectorIOS *replacement =
                [[TemplateProtectorIOS alloc] initWithMasterKey:masterKey
                                                       version:version
                                                         scope:scope
                                                         error:&error];

        if (!replacement) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_SETUP_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        [self.protector wipe];
        self.protector = replacement;
        [self wipeEmployees];

        [self sendOK:@{
            @"success": @YES,
            @"templateVersion": @(version),
            @"templateBits": @([TemplateProtectorIOS templateBits]),
            @"scheme": [TemplateProtectorIOS scheme],
            @"protectionScope": scope,
            @"keyId": replacement.keyId
        } callbackId:command.callbackId];
    }];
}

- (void)activateProtectionScope:(CDVInvokedUrlCommand *)command {
    [self.commandDelegate runInBackground:^{
        NSString *scope =
                [TemplateProtectorIOS normalizeScope:
                 [self argString:command.arguments index:0 fallback:@"default"]];

        NSError *error = nil;

        if (![SecureProtectionStoreIOS activateScope:scope error:&error]) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_ACTIVATE_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        FaceIDSavedProtection *saved =
                [SecureProtectionStoreIOS loadScope:scope error:&error];

        if (!saved) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_ACTIVATE_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        TemplateProtectorIOS *replacement =
                [[TemplateProtectorIOS alloc] initWithMasterKey:saved.masterKey
                                                       version:saved.version
                                                         scope:saved.scope
                                                         error:&error];

        if (!replacement) {
            [self sendError:[self messageWithPrefix:@"PROTECTION_ACTIVATE_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        [self.protector wipe];
        self.protector = replacement;
        [self wipeEmployees];

        [self sendOK:@{
            @"success": @YES,
            @"templateVersion": @(saved.version),
            @"templateBits": @([TemplateProtectorIOS templateBits]),
            @"scheme": [TemplateProtectorIOS scheme],
            @"protectionScope": saved.scope,
            @"keyId": replacement.keyId
        } callbackId:command.callbackId];
    }];
}

- (void)clearProtectionKey:(CDVInvokedUrlCommand *)command {
    NSString *requested = [self argString:command.arguments index:0 fallback:@""];
    NSString *scope = requested.length > 0
        ? [TemplateProtectorIOS normalizeScope:requested]
        : [SecureProtectionStoreIOS activeScope];

    if (scope.length > 0) {
        [SecureProtectionStoreIOS clearScope:scope];
    }

    [self.protector wipe];
    self.protector = nil;
    [self wipeEmployees];

    [self sendOK:@{
        @"success": @YES,
        @"protectionScope": scope ?: @""
    } callbackId:command.callbackId];
}

#pragma mark - Availability

- (void)isAvailable:(CDVInvokedUrlCommand *)command {
    NSString *modelPath =
            [[NSBundle mainBundle] pathForResource:@"facenet"
                                           ofType:@"tflite"];

    [self sendOK:@{
        @"available": @(modelPath != nil),
        @"embeddingSize": @([FaceNetEngineIOS embeddingSize]),
        @"captureMode": @"IOS_NATIVE_CAMERA",
        @"model": @"FACENET_128D_SLIM",
        @"preprocess": @"VISION_LANDMARK_ALIGN_5PT_TTA_160_V1",
        @"templateProtection": [TemplateProtectorIOS scheme],
        @"templateBits": @([TemplateProtectorIOS templateBits]),
        @"protectionConfigured": @([SecureProtectionStoreIOS existsActive]),
        @"protectionScope": [SecureProtectionStoreIOS activeScope] ?: @""
    } callbackId:command.callbackId];
}

#pragma mark - FaceNet / Vision

- (BOOL)ensureEngine:(NSError **)error {
    if (self.engine) return YES;
    FaceNetEngineIOS *engine = [[FaceNetEngineIOS alloc] initWithError:error];
    if (!engine) return NO;
    self.engine = engine;
    return YES;
}

- (UIImage *)normalizeOrientation:(UIImage *)image {
    if (image.imageOrientation == UIImageOrientationUp) {
        return image;
    }

    UIGraphicsBeginImageContextWithOptions(image.size, NO, image.scale);
    [image drawInRect:(CGRect){CGPointZero, image.size}];
    UIImage *normalized = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return normalized;
}

- (nullable UIImage *)imageFromBase64:(NSString *)imageBase64 error:(NSError **)error {
    NSString *text = [imageBase64 ?: @"" stringByTrimmingCharactersInSet:
                      [NSCharacterSet whitespaceAndNewlineCharacterSet]];

    NSRange comma = [text rangeOfString:@","];
    if ([text hasPrefix:@"data:"] && comma.location != NSNotFound) {
        text = [text substringFromIndex:comma.location + 1];
    }

    NSData *data = [[NSData alloc] initWithBase64EncodedString:text
                                                       options:NSDataBase64DecodingIgnoreUnknownCharacters];

    UIImage *image = data ? [UIImage imageWithData:data] : nil;

    if (!image) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Image"
                                         code:-20
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"ImageBase64 is empty or invalid."
            }];
        }
        return nil;
    }

    return [self normalizeOrientation:image];
}

- (CGPoint)centroidForRegion:(VNFaceLandmarkRegion2D *)region
                 observation:(VNFaceObservation *)observation
                   imageSize:(CGSize)imageSize {

    const vector_float2 *points = region.normalizedPoints;
    NSUInteger count = region.pointCount;

    if (!points || count == 0) return CGPointMake(NAN, NAN);

    double sx = 0.0;
    double sy = 0.0;

    for (NSUInteger i = 0; i < count; i++) {
        double nx = observation.boundingBox.origin.x +
                points[i].x * observation.boundingBox.size.width;

        double ny = observation.boundingBox.origin.y +
                points[i].y * observation.boundingBox.size.height;

        sx += nx * imageSize.width;
        sy += (1.0 - ny) * imageSize.height;
    }

    return CGPointMake(sx / count, sy / count);
}

- (BOOL)mouthCornersForRegion:(VNFaceLandmarkRegion2D *)region
                  observation:(VNFaceObservation *)observation
                    imageSize:(CGSize)imageSize
                         left:(CGPoint *)left
                        right:(CGPoint *)right {

    const vector_float2 *points = region.normalizedPoints;
    NSUInteger count = region.pointCount;

    if (!points || count < 2) return NO;

    CGPoint minPoint = CGPointMake(CGFLOAT_MAX, 0);
    CGPoint maxPoint = CGPointMake(-CGFLOAT_MAX, 0);

    for (NSUInteger i = 0; i < count; i++) {
        double nx = observation.boundingBox.origin.x +
                points[i].x * observation.boundingBox.size.width;

        double ny = observation.boundingBox.origin.y +
                points[i].y * observation.boundingBox.size.height;

        CGPoint p = CGPointMake(
                nx * imageSize.width,
                (1.0 - ny) * imageSize.height
        );

        if (p.x < minPoint.x) minPoint = p;
        if (p.x > maxPoint.x) maxPoint = p;
    }

    if (left) *left = minPoint;
    if (right) *right = maxPoint;
    return YES;
}

- (nullable NSValue *)similarityTransformFromSource:(NSArray<NSValue *> *)src
                                             target:(NSArray<NSValue *> *)dst {

    if (src.count != dst.count || src.count < 2) return nil;

    double srcMeanX = 0, srcMeanY = 0, dstMeanX = 0, dstMeanY = 0;

    for (NSUInteger i = 0; i < src.count; i++) {
        CGPoint s = src[i].CGPointValue;
        CGPoint d = dst[i].CGPointValue;
        srcMeanX += s.x; srcMeanY += s.y;
        dstMeanX += d.x; dstMeanY += d.y;
    }

    srcMeanX /= src.count; srcMeanY /= src.count;
    dstMeanX /= dst.count; dstMeanY /= dst.count;

    double denom = 0, numA = 0, numB = 0;

    for (NSUInteger i = 0; i < src.count; i++) {
        CGPoint sp = src[i].CGPointValue;
        CGPoint dp = dst[i].CGPointValue;

        double sx = sp.x - srcMeanX;
        double sy = sp.y - srcMeanY;
        double dx = dp.x - dstMeanX;
        double dy = dp.y - dstMeanY;

        denom += sx * sx + sy * sy;
        numA += sx * dx + sy * dy;
        numB += sx * dy - sy * dx;
    }

    if (denom < 1e-9) return nil;

    double a = numA / denom;
    double b = numB / denom;

    double tx = dstMeanX - a * srcMeanX + b * srcMeanY;
    double ty = dstMeanY - b * srcMeanX - a * srcMeanY;

    CGAffineTransform t = CGAffineTransformMake(
            a,
            b,
            -b,
            a,
            tx,
            ty
    );

    return [NSValue valueWithCGAffineTransform:t];
}

- (UIImage *)cropFaceFallback:(UIImage *)image observation:(VNFaceObservation *)face {
    CGSize size = image.size;

    CGRect box = CGRectMake(
            face.boundingBox.origin.x * size.width,
            (1.0 - face.boundingBox.origin.y - face.boundingBox.size.height) * size.height,
            face.boundingBox.size.width * size.width,
            face.boundingBox.size.height * size.height
    );

    CGFloat side = MAX(box.size.width, box.size.height) * 1.45;
    CGPoint center = CGPointMake(CGRectGetMidX(box), CGRectGetMidY(box));

    CGRect square = CGRectMake(
            center.x - side / 2.0,
            center.y - side / 2.0,
            side,
            side
    );

    square = CGRectIntersection(
            square,
            CGRectMake(0, 0, size.width, size.height)
    );

    CGImageRef crop = CGImageCreateWithImageInRect(image.CGImage, square);
    if (!crop) return nil;

    UIImage *cropped = [UIImage imageWithCGImage:crop scale:1.0 orientation:UIImageOrientationUp];
    CGImageRelease(crop);

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(160, 160), YES, 1.0);
    [cropped drawInRect:CGRectMake(0, 0, 160, 160)];
    UIImage *resized = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return resized;
}

- (nullable UIImage *)detectAndAlignSingleFace:(UIImage *)source error:(NSError **)error {
    UIImage *image = [self normalizeOrientation:source];

    VNDetectFaceLandmarksRequest *request = [VNDetectFaceLandmarksRequest new];
    VNImageRequestHandler *handler =
            [[VNImageRequestHandler alloc] initWithCGImage:image.CGImage
                                               orientation:kCGImagePropertyOrientationUp
                                                   options:@{}];

    NSError *visionError = nil;

    if (![handler performRequests:@[request] error:&visionError]) {
        if (error) *error = visionError;
        return nil;
    }

    NSArray<VNFaceObservation *> *faces = (NSArray<VNFaceObservation *> *)request.results;

    if (faces.count == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Vision"
                                         code:-21
                                     userInfo:@{NSLocalizedDescriptionKey: @"NO_FACE: No face detected."}];
        }
        return nil;
    }

    if (faces.count != 1) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Vision"
                                         code:-22
                                     userInfo:@{NSLocalizedDescriptionKey: @"MULTIPLE_FACES: Exactly one face is required."}];
        }
        return nil;
    }

    VNFaceObservation *face = faces.firstObject;
    VNFaceLandmarks2D *landmarks = face.landmarks;

    if (!landmarks.leftEye || !landmarks.rightEye) {
        return [self cropFaceFallback:image observation:face];
    }

    CGPoint eyeA = [self centroidForRegion:landmarks.leftEye
                               observation:face
                                 imageSize:image.size];

    CGPoint eyeB = [self centroidForRegion:landmarks.rightEye
                               observation:face
                                 imageSize:image.size];

    CGPoint imageLeftEye = eyeA.x <= eyeB.x ? eyeA : eyeB;
    CGPoint imageRightEye = eyeA.x <= eyeB.x ? eyeB : eyeA;

    NSMutableArray<NSValue *> *src = [NSMutableArray array];
    NSMutableArray<NSValue *> *dst = [NSMutableArray array];

    if (landmarks.nose && landmarks.outerLips) {
        CGPoint nose = [self centroidForRegion:landmarks.nose
                                   observation:face
                                     imageSize:image.size];

        CGPoint mouthLeft, mouthRight;

        if ([self mouthCornersForRegion:landmarks.outerLips
                            observation:face
                              imageSize:image.size
                                   left:&mouthLeft
                                  right:&mouthRight]) {

            [src addObjectsFromArray:@[
                [NSValue valueWithCGPoint:imageLeftEye],
                [NSValue valueWithCGPoint:imageRightEye],
                [NSValue valueWithCGPoint:nose],
                [NSValue valueWithCGPoint:mouthLeft],
                [NSValue valueWithCGPoint:mouthRight]
            ]];

            [dst addObjectsFromArray:@[
                [NSValue valueWithCGPoint:CGPointMake(54.7066, 73.8519)],
                [NSValue valueWithCGPoint:CGPointMake(105.0454, 73.5734)],
                [NSValue valueWithCGPoint:CGPointMake(80.0360, 102.4809)],
                [NSValue valueWithCGPoint:CGPointMake(59.3561, 131.9507)],
                [NSValue valueWithCGPoint:CGPointMake(101.0427, 131.7201)]
            ]];
        }
    }

    if (src.count == 0 && landmarks.nose) {
        CGPoint nose = [self centroidForRegion:landmarks.nose
                                   observation:face
                                     imageSize:image.size];

        [src addObjectsFromArray:@[
            [NSValue valueWithCGPoint:imageLeftEye],
            [NSValue valueWithCGPoint:imageRightEye],
            [NSValue valueWithCGPoint:nose]
        ]];

        [dst addObjectsFromArray:@[
            [NSValue valueWithCGPoint:CGPointMake(54.7066, 73.8519)],
            [NSValue valueWithCGPoint:CGPointMake(105.0454, 73.5734)],
            [NSValue valueWithCGPoint:CGPointMake(80.0360, 102.4809)]
        ]];
    }

    if (src.count == 0) {
        [src addObjectsFromArray:@[
            [NSValue valueWithCGPoint:imageLeftEye],
            [NSValue valueWithCGPoint:imageRightEye]
        ]];

        [dst addObjectsFromArray:@[
            [NSValue valueWithCGPoint:CGPointMake(54.7066, 73.8519)],
            [NSValue valueWithCGPoint:CGPointMake(105.0454, 73.5734)]
        ]];
    }

    NSValue *transformValue =
            [self similarityTransformFromSource:src target:dst];

    if (!transformValue) {
        return [self cropFaceFallback:image observation:face];
    }

    CGAffineTransform transform = transformValue.CGAffineTransformValue;

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(160, 160), YES, 1.0);
    CGContextRef context = UIGraphicsGetCurrentContext();

    [[UIColor blackColor] setFill];
    CGContextFillRect(context, CGRectMake(0, 0, 160, 160));
    CGContextConcatCTM(context, transform);
    [image drawAtPoint:CGPointZero];

    UIImage *aligned = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return aligned;
}

- (UIImage *)mirroredImage:(UIImage *)image {
    UIGraphicsBeginImageContextWithOptions(image.size, YES, 1.0);
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    CGContextTranslateCTM(ctx, image.size.width, 0);
    CGContextScaleCTM(ctx, -1.0, 1.0);
    [image drawAtPoint:CGPointZero];
    UIImage *out = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return out;
}

- (BOOL)embeddingWithFlipTTAForAlignedFace:(UIImage *)face
                                   output:(float *)out
                                    error:(NSError **)error {

    float normal[128] = {0};
    float flipped[128] = {0};

    if (![self.engine embeddingForImage:face output:normal count:128 error:error]) {
        return NO;
    }

    UIImage *mirror = [self mirroredImage:face];

    if (![self.engine embeddingForImage:mirror output:flipped count:128 error:error]) {
        memset(normal, 0, sizeof(normal));
        return NO;
    }

    double sum = 0.0;

    for (int i = 0; i < 128; i++) {
        out[i] = (normal[i] + flipped[i]) * 0.5f;
        sum += out[i] * out[i];
    }

    double norm = sqrt(MAX(sum, 1e-12));

    for (int i = 0; i < 128; i++) {
        out[i] = (float)(out[i] / norm);
    }

    memset(normal, 0, sizeof(normal));
    memset(flipped, 0, sizeof(flipped));

    return YES;
}

#pragma mark - Descriptor / employee loading / matching

- (void)createDescriptor:(CDVInvokedUrlCommand *)command {
    [self.commandDelegate runInBackground:^{
        NSError *error = nil;

        if (![self ensureEngine:&error] || ![self ensureProtector:&error]) {
            [self sendError:[self messageWithPrefix:@"DESCRIPTOR_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        NSString *base64 = [self argString:command.arguments index:0 fallback:@""];
        UIImage *image = [self imageFromBase64:base64 error:&error];
        UIImage *face = image ? [self detectAndAlignSingleFace:image error:&error] : nil;

        if (!face) {
            [self sendError:[self messageWithPrefix:@"DESCRIPTOR_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        float embedding[128] = {0};

        if (![self embeddingWithFlipTTAForAlignedFace:face output:embedding error:&error]) {
            [self sendError:[self messageWithPrefix:@"DESCRIPTOR_FAILED" error:error]
                 callbackId:command.callbackId];
            memset(embedding, 0, sizeof(embedding));
            return;
        }

        NSData *code =
                [self.protector protectEmbedding:embedding
                                           count:128
                                           error:&error];

        memset(embedding, 0, sizeof(embedding));

        if (!code) {
            [self sendError:[self messageWithPrefix:@"DESCRIPTOR_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        NSString *protectedTemplate = [self.protector encodeCode:code];

        [self sendOK:@{
            @"success": @YES,
            @"descriptor": protectedTemplate,
            @"embeddingSize": @128,
            @"templateBits": @([TemplateProtectorIOS templateBits]),
            @"templateVersion": @(self.protector.version),
            @"templateProtection": [TemplateProtectorIOS scheme],
            @"protectionScope": self.protector.scope,
            @"keyId": self.protector.keyId
        } callbackId:command.callbackId];
    }];
}

- (nullable NSDictionary *)protectedTemplateResultForImage:(UIImage *)image
                                                          error:(NSError **)error {

    if (![self ensureEngine:error] || ![self ensureProtector:error]) {
        return nil;
    }

    UIImage *face =
            [self detectAndAlignSingleFace:image
                                    error:error];

    if (!face) return nil;

    float embedding[128] = {0};

    if (![self embeddingWithFlipTTAForAlignedFace:face
                                           output:embedding
                                            error:error]) {
        memset(embedding, 0, sizeof(embedding));
        return nil;
    }

    NSData *code =
            [self.protector protectEmbedding:embedding
                                       count:128
                                       error:error];

    memset(embedding, 0, sizeof(embedding));

    if (!code) return nil;

    NSString *protectedTemplate =
            [self.protector encodeCode:code];

    return @{
        @"success": @YES,
        @"protectedTemplate": protectedTemplate,
        // Compatibility alias: PT2 protected value, never raw FaceNet floats.
        @"descriptor": protectedTemplate,
        @"embeddingSize": @128,
        @"templateBits": @([TemplateProtectorIOS templateBits]),
        @"templateVersion": @(self.protector.version),
        @"templateProtection": [TemplateProtectorIOS scheme],
        @"protectionScope": self.protector.scope,
        @"keyId": self.protector.keyId
    };
}

- (void)setEmployees:(CDVInvokedUrlCommand *)command {
    [self.commandDelegate runInBackground:^{
        NSError *error = nil;

        if (![self ensureProtector:&error]) {
            [self sendError:[self messageWithPrefix:@"SET_EMPLOYEES_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        NSString *json = [self argString:command.arguments index:0 fallback:@""];
        NSData *data = [json dataUsingEncoding:NSUTF8StringEncoding];

        id parsedJSON =
                data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:&error] : nil;

        if (![parsedJSON isKindOfClass:[NSArray class]]) {
            [self sendError:[self messageWithPrefix:@"SET_EMPLOYEES_FAILED"
                                              error:error ?: [NSError errorWithDomain:@"FaceIDNative.JSON"
                                                                                 code:-30
                                                                             userInfo:@{
                NSLocalizedDescriptionKey: @"EmployeesJson must be a JSON array."
            }]]
                 callbackId:command.callbackId];
            return;
        }

        NSMutableArray<FaceIDEmployeeTemplate *> *loaded = [NSMutableArray array];
        NSInteger skipped = 0;

        for (id raw in (NSArray *)parsedJSON) {
            if (![raw isKindOfClass:[NSDictionary class]]) {
                skipped++;
                continue;
            }

            NSDictionary *item = (NSDictionary *)raw;

            id activeValue = item[@"Active"] ?: item[@"active"];
            if (activeValue && [activeValue respondsToSelector:@selector(boolValue)] &&
                ![activeValue boolValue]) {
                continue;
            }

            NSString *employeeId = [NSString stringWithFormat:@"%@",
                item[@"EmployeeId"] ?: item[@"employeeId"] ?: @""];

            NSString *name = [NSString stringWithFormat:@"%@",
                item[@"Name"] ?: item[@"EmployeeName"] ?: item[@"name"] ?: @""];

            id templateValue =
                item[@"ProtectedTemplate"] ?:
                item[@"FaceTemplate"] ?:
                item[@"FaceDescriptorJson"] ?:
                item[@"DescriptorJson"] ?:
                item[@"descriptor"];

            NSString *templateText =
                templateValue ? [NSString stringWithFormat:@"%@", templateValue] : @"";

            NSData *protectedCode =
                    [self.protector decodeTemplate:templateText];

            if (employeeId.length == 0 || !protectedCode) {
                skipped++;
                continue;
            }

            FaceIDEmployeeTemplate *employee = [FaceIDEmployeeTemplate new];
            employee.employeeId = employeeId;
            employee.name = name;
            employee.protectedTemplate = protectedCode;
            [loaded addObject:employee];
        }

        self.employees = [loaded copy];

        [self sendOK:@{
            @"success": @YES,
            @"loaded": @(loaded.count),
            @"skipped": @(skipped),
            @"templateVersion": @(self.protector.version),
            @"templateProtection": [TemplateProtectorIOS scheme],
            @"protectionScope": self.protector.scope,
            @"keyId": self.protector.keyId
        } callbackId:command.callbackId];
    }];
}

- (nullable NSDictionary *)matchImage:(UIImage *)image
                           threshold:(double)threshold
                              minGap:(double)minGap
                               error:(NSError **)error {

    if (![self ensureEngine:error] || ![self ensureProtector:error]) {
        return nil;
    }

    if (self.employees.count == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.Match"
                                         code:-40
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"NO_EMPLOYEES_LOADED: Call FaceID_SetEmployees first."
            }];
        }
        return nil;
    }

    UIImage *face = [self detectAndAlignSingleFace:image error:error];
    if (!face) return nil;

    float embedding[128] = {0};

    if (![self embeddingWithFlipTTAForAlignedFace:face
                                           output:embedding
                                            error:error]) {
        return nil;
    }

    NSData *current =
            [self.protector protectEmbedding:embedding
                                       count:128
                                       error:error];

    memset(embedding, 0, sizeof(embedding));

    if (!current) return nil;

    FaceIDEmployeeTemplate *best = nil;
    double bestSimilarity = -1.0;
    double secondSimilarity = -1.0;

    for (FaceIDEmployeeTemplate *employee in self.employees) {
        double similarity =
                [self.protector similarityBetween:current
                                              and:employee.protectedTemplate];

        if (similarity > bestSimilarity) {
            secondSimilarity = bestSimilarity;
            bestSimilarity = similarity;
            best = employee;
        } else if (similarity > secondSimilarity) {
            secondSimilarity = similarity;
        }
    }

    BOOL aboveThreshold = best && bestSimilarity >= threshold;
    BOOL unambiguous = secondSimilarity < 0.0 ||
            (bestSimilarity - secondSimilarity) >= minGap;
    BOOL found = aboveThreshold && unambiguous;

    return @{
        @"success": @YES,
        @"found": @(found),
        @"employeeId": found && best ? best.employeeId : @"",
        @"employeeName": found && best ? best.name : @"",
        @"similarity": @(bestSimilarity),
        @"secondSimilarity": @(secondSimilarity),
        @"threshold": @(threshold),
        @"minGap": @(minGap),
        @"reason": found ? @"MATCH" : (aboveThreshold ? @"AMBIGUOUS" : @"BELOW_THRESHOLD"),
        @"preprocess": @"VISION_LANDMARK_ALIGN_5PT_TTA_160_V1",
        @"model": @"FACENET_128D_SLIM",
        @"templateProtection": [TemplateProtectorIOS scheme],
        @"templateVersion": @(self.protector.version),
        @"protectionScope": self.protector.scope,
        @"keyId": self.protector.keyId,
        @"mirrorUsed": @YES
    };
}

- (void)findBestMatch:(CDVInvokedUrlCommand *)command {
    [self.commandDelegate runInBackground:^{
        NSError *error = nil;
        NSString *base64 = [self argString:command.arguments index:0 fallback:@""];
        double threshold = [self argDouble:command.arguments index:1 fallback:kDefaultThreshold];
        double minGap = [self argDouble:command.arguments index:2 fallback:kDefaultMinGap];

        UIImage *image = [self imageFromBase64:base64 error:&error];
        NSDictionary *result =
                image ? [self matchImage:image threshold:threshold minGap:minGap error:&error] : nil;

        if (!result) {
            [self sendError:[self messageWithPrefix:@"MATCH_FAILED" error:error]
                 callbackId:command.callbackId];
            return;
        }

        [self sendOK:result callbackId:command.callbackId];
    }];
}

#pragma mark - Native camera

- (void)captureTemplate:(CDVInvokedUrlCommand *)command {

    if (![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [self sendError:@"CAMERA_NOT_AVAILABLE"
             callbackId:command.callbackId];
        return;
    }

    self.pendingCaptureCallbackId = command.callbackId;
    self.pendingCaptureTemplate = YES;
    self.pendingThreshold = kDefaultThreshold;
    self.pendingMinGap = kDefaultMinGap;

    dispatch_async(dispatch_get_main_queue(), ^{
        UIImagePickerController *picker = [UIImagePickerController new];
        picker.sourceType = UIImagePickerControllerSourceTypeCamera;
        picker.cameraDevice = UIImagePickerControllerCameraDeviceFront;
        picker.cameraCaptureMode = UIImagePickerControllerCameraCaptureModePhoto;
        picker.allowsEditing = NO;
        picker.delegate = self;
        self.picker = picker;

        [self.viewController presentViewController:picker
                                         animated:YES
                                       completion:nil];
    });
}

- (void)captureAndMatch:(CDVInvokedUrlCommand *)command {
    double threshold = [self argDouble:command.arguments index:0 fallback:kDefaultThreshold];
    double minGap = [self argDouble:command.arguments index:1 fallback:kDefaultMinGap];

    if (![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [self sendError:@"CAMERA_NOT_AVAILABLE" callbackId:command.callbackId];
        return;
    }

    self.pendingCaptureCallbackId = command.callbackId;
    self.pendingCaptureTemplate = NO;
    self.pendingThreshold = threshold;
    self.pendingMinGap = minGap;

    dispatch_async(dispatch_get_main_queue(), ^{
        UIImagePickerController *picker = [UIImagePickerController new];
        picker.sourceType = UIImagePickerControllerSourceTypeCamera;
        picker.cameraDevice = UIImagePickerControllerCameraDeviceFront;
        picker.cameraCaptureMode = UIImagePickerControllerCameraCaptureModePhoto;
        picker.allowsEditing = NO;
        picker.delegate = self;
        self.picker = picker;

        [self.viewController presentViewController:picker
                                         animated:YES
                                       completion:nil];
    });
}

- (void)imagePickerController:(UIImagePickerController *)picker
didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {

    UIImage *image = info[UIImagePickerControllerOriginalImage];
    NSString *callbackId = self.pendingCaptureCallbackId;
    double threshold = self.pendingThreshold;
    double minGap = self.pendingMinGap;
    BOOL captureTemplate = self.pendingCaptureTemplate;

    self.pendingCaptureCallbackId = nil;
    self.pendingCaptureTemplate = NO;
    self.picker = nil;

    [picker dismissViewControllerAnimated:YES completion:^{
        [self.commandDelegate runInBackground:^{
            NSError *error = nil;
            UIImage *normalized =
                    image ? [self normalizeOrientation:image] : nil;

            NSDictionary *result = nil;

            if (normalized) {
                if (captureTemplate) {
                    result =
                            [self protectedTemplateResultForImage:normalized
                                                           error:&error];
                } else {
                    result =
                            [self matchImage:normalized
                                  threshold:threshold
                                     minGap:minGap
                                      error:&error];
                }
            }

            if (!result) {
                NSString *prefix =
                        captureTemplate
                            ? @"CAPTURE_TEMPLATE_FAILED"
                            : @"CAPTURE_MATCH_FAILED";

                [self sendError:[self messageWithPrefix:prefix error:error]
                     callbackId:callbackId];
                return;
            }

            [self sendOK:result callbackId:callbackId];
        }];
    }];
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    NSString *callbackId = self.pendingCaptureCallbackId;
    self.pendingCaptureCallbackId = nil;
    self.pendingCaptureTemplate = NO;
    self.picker = nil;

    [picker dismissViewControllerAnimated:YES completion:^{
        if (callbackId.length > 0) {
            [self sendError:@"CAPTURE_CANCELLED" callbackId:callbackId];
        }
    }];
}

#pragma mark - Cleanup

- (void)clearEmployees:(CDVInvokedUrlCommand *)command {
    [self wipeEmployees];
    [self sendOK:@{@"success": @YES, @"count": @0}
      callbackId:command.callbackId];
}

- (void)dispose:(CDVInvokedUrlCommand *)command {
    [self wipeEmployees];
    [self.protector wipe];
    self.protector = nil;
    self.engine = nil;

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.picker.presentingViewController) {
            [self.picker dismissViewControllerAnimated:NO completion:nil];
        }
        self.picker = nil;
        self.pendingCaptureCallbackId = nil;
        self.pendingCaptureTemplate = NO;
    });

    [self sendOK:@{@"success": @YES}
      callbackId:command.callbackId];
}

@end

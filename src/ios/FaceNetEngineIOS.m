#import "FaceNetEngineIOS.h"

#if __has_include(<TensorFlowLiteObjC/TFLTensorFlowLite.h>)
#import <TensorFlowLiteObjC/TFLTensorFlowLite.h>
#else
#import "TFLTensorFlowLite.h"
#import <math.h>
#endif

static const NSInteger kFaceNetInput = 160;
static const NSInteger kFaceNetEmbedding = 128;

@interface FaceNetEngineIOS ()
@property(nonatomic, strong) TFLInterpreter *interpreter;
@end

@implementation FaceNetEngineIOS

+ (NSInteger)inputSize {
    return kFaceNetInput;
}

+ (NSInteger)embeddingSize {
    return kFaceNetEmbedding;
}

- (nullable instancetype)initWithError:(NSError **)error {
    self = [super init];
    if (!self) return nil;

    NSString *modelPath =
            [[NSBundle mainBundle] pathForResource:@"facenet"
                                           ofType:@"tflite"];

    if (!modelPath) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-1
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"facenet.tflite missing from iOS app bundle."
            }];
        }
        return nil;
    }

    TFLInterpreterOptions *options = [TFLInterpreterOptions new];
    options.numberOfThreads = 4;
    options.useXNNPACK = YES;

    NSError *localError = nil;
    _interpreter = [[TFLInterpreter alloc] initWithModelPath:modelPath
                                                    options:options
                                                  delegates:@[]
                                                      error:&localError];

    if (!_interpreter || localError) {
        if (error) *error = localError;
        return nil;
    }

    if (![_interpreter allocateTensorsWithError:&localError] || localError) {
        if (error) *error = localError;
        return nil;
    }

    TFLTensor *input =
            [_interpreter inputTensorAtIndex:0 error:&localError];

    if (!input || localError) {
        if (error) *error = localError;
        return nil;
    }

    NSArray<NSNumber *> *inputShape =
            [input shapeWithError:&localError];

    if (localError ||
        inputShape.count != 4 ||
        inputShape[0].integerValue != 1 ||
        inputShape[1].integerValue != kFaceNetInput ||
        inputShape[2].integerValue != kFaceNetInput ||
        inputShape[3].integerValue != 3) {

        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-2
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Unexpected FaceNet input. Expected [1,160,160,3]."
            }];
        }
        return nil;
    }

    TFLTensor *output =
            [_interpreter outputTensorAtIndex:0 error:&localError];

    NSArray<NSNumber *> *outputShape =
            output ? [output shapeWithError:&localError] : nil;

    if (localError ||
        outputShape.count != 2 ||
        outputShape[0].integerValue != 1 ||
        outputShape[1].integerValue != kFaceNetEmbedding) {

        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-3
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Unexpected FaceNet output. Expected [1,128]."
            }];
        }
        return nil;
    }

    return self;
}

- (UIImage *)normalized160Image:(UIImage *)image {
    CGSize target = CGSizeMake(kFaceNetInput, kFaceNetInput);
    UIGraphicsBeginImageContextWithOptions(target, YES, 1.0);
    [image drawInRect:CGRectMake(0, 0, target.width, target.height)];
    UIImage *result = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return result;
}

- (BOOL)embeddingForImage:(UIImage *)image
                   output:(float *)output
                    count:(NSUInteger)count
                    error:(NSError **)error {

    if (!image || !output || count != kFaceNetEmbedding) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-4
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Invalid FaceNet input/output."
            }];
        }
        return NO;
    }

    UIImage *resized = [self normalized160Image:image];
    CGImageRef cgImage = resized.CGImage;

    if (!cgImage) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-5
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Could not render face image."
            }];
        }
        return NO;
    }

    const size_t width = kFaceNetInput;
    const size_t height = kFaceNetInput;
    const size_t bytesPerRow = width * 4;

    NSMutableData *rgba =
            [NSMutableData dataWithLength:height * bytesPerRow];

    CGColorSpaceRef colorSpace =
            CGColorSpaceCreateDeviceRGB();

    CGContextRef context =
            CGBitmapContextCreate(
                    rgba.mutableBytes,
                    width,
                    height,
                    8,
                    bytesPerRow,
                    colorSpace,
                    kCGImageAlphaPremultipliedLast |
                    kCGBitmapByteOrder32Big
            );

    CGColorSpaceRelease(colorSpace);

    if (!context) {
        if (error) {
            *error = [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                         code:-6
                                     userInfo:@{
                NSLocalizedDescriptionKey: @"Could not allocate image buffer."
            }];
        }
        return NO;
    }

    CGContextDrawImage(
            context,
            CGRectMake(0, 0, width, height),
            cgImage
    );
    CGContextRelease(context);

    NSMutableData *inputData =
            [NSMutableData dataWithLength:
             width * height * 3 * sizeof(float)];

    float *dst = inputData.mutableBytes;
    const uint8_t *src = rgba.bytes;

    for (size_t i = 0; i < width * height; i++) {
        const uint8_t *px = src + i * 4;
        *dst++ = ((float)px[0] - 127.5f) / 127.5f;
        *dst++ = ((float)px[1] - 127.5f) / 127.5f;
        *dst++ = ((float)px[2] - 127.5f) / 127.5f;
    }

    NSError *localError = nil;

    @synchronized (self.interpreter) {
        TFLTensor *input =
                [self.interpreter inputTensorAtIndex:0
                                              error:&localError];

        if (!input || localError ||
            ![input copyData:inputData error:&localError]) {
            if (error) *error = localError;
            return NO;
        }

        if (![self.interpreter invokeWithError:&localError] ||
            localError) {
            if (error) *error = localError;
            return NO;
        }

        TFLTensor *outputTensor =
                [self.interpreter outputTensorAtIndex:0
                                               error:&localError];

        NSData *outputData =
                outputTensor
                    ? [outputTensor dataWithError:&localError]
                    : nil;

        if (!outputData ||
            localError ||
            outputData.length < kFaceNetEmbedding * sizeof(float)) {
            if (error) {
                *error = localError ?: [NSError errorWithDomain:@"FaceIDNative.FaceNet"
                                                           code:-7
                                                       userInfo:@{
                    NSLocalizedDescriptionKey: @"Invalid FaceNet output buffer."
                }];
            }
            return NO;
        }

        memcpy(
                output,
                outputData.bytes,
                kFaceNetEmbedding * sizeof(float)
        );
    }

    double sum = 0.0;

    for (int i = 0; i < kFaceNetEmbedding; i++) {
        sum += output[i] * output[i];
    }

    double norm = sqrt(MAX(sum, 1e-12));

    for (int i = 0; i < kFaceNetEmbedding; i++) {
        output[i] = (float)(output[i] / norm);
    }

    return YES;
}

@end

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface FaceNetEngineIOS : NSObject

+ (NSInteger)inputSize;
+ (NSInteger)embeddingSize;

- (nullable instancetype)initWithError:(NSError **)error;

- (BOOL)embeddingForImage:(UIImage *)image
                   output:(float *)output
                    count:(NSUInteger)count
                    error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END

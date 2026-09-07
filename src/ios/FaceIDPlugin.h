#import <Cordova/CDVPlugin.h>
#import <UIKit/UIKit.h>

@interface FaceIDPlugin : CDVPlugin <UIImagePickerControllerDelegate, UINavigationControllerDelegate>

- (void)isAvailable:(CDVInvokedUrlCommand *)command;
- (void)setProtectionKey:(CDVInvokedUrlCommand *)command;
- (void)activateProtectionScope:(CDVInvokedUrlCommand *)command;
- (void)clearProtectionKey:(CDVInvokedUrlCommand *)command;
- (void)createDescriptor:(CDVInvokedUrlCommand *)command;
- (void)setEmployees:(CDVInvokedUrlCommand *)command;
- (void)findBestMatch:(CDVInvokedUrlCommand *)command;
- (void)captureAndMatch:(CDVInvokedUrlCommand *)command;
- (void)clearEmployees:(CDVInvokedUrlCommand *)command;
- (void)dispose:(CDVInvokedUrlCommand *)command;

@end

var exec = require('cordova/exec');
var SERVICE = 'FaceIDPlugin';

exports.isAvailable = function (success, error) {
    exec(success, error, SERVICE, 'isAvailable', []);
};

exports.setProtectionKey = function (
    protectionKey,
    templateVersion,
    protectionScope,
    success,
    error
) {
    // Backward compatible with v1.0.9 call shape:
    // setProtectionKey(key, version, success, error)
    if (typeof protectionScope === 'function') {
        error = success;
        success = protectionScope;
        protectionScope = 'default';
    }

    exec(
        success,
        error,
        SERVICE,
        'setProtectionKey',
        [
            protectionKey,
            templateVersion,
            protectionScope || 'default'
        ]
    );
};

exports.activateProtectionScope = function (
    protectionScope,
    success,
    error
) {
    exec(
        success,
        error,
        SERVICE,
        'activateProtectionScope',
        [protectionScope || 'default']
    );
};

exports.clearProtectionKey = function (
    protectionScope,
    success,
    error
) {
    // Backward compatible: clearProtectionKey(success, error)
    if (typeof protectionScope === 'function') {
        error = success;
        success = protectionScope;
        protectionScope = '';
    }

    exec(
        success,
        error,
        SERVICE,
        'clearProtectionKey',
        [protectionScope || '']
    );
};

exports.createDescriptor = function (imageBase64, success, error) {
    exec(success, error, SERVICE, 'createDescriptor', [imageBase64]);
};

exports.setEmployees = function (employeesJson, success, error) {
    exec(success, error, SERVICE, 'setEmployees', [employeesJson]);
};

exports.findBestMatch = function (
    imageBase64,
    threshold,
    minGap,
    success,
    error
) {
    exec(
        success,
        error,
        SERVICE,
        'findBestMatch',
        [imageBase64, threshold, minGap]
    );
};

exports.captureAndMatch = function (
    threshold,
    minGap,
    success,
    error
) {
    exec(
        success,
        error,
        SERVICE,
        'captureAndMatch',
        [threshold, minGap]
    );
};

exports.clearEmployees = function (success, error) {
    exec(success, error, SERVICE, 'clearEmployees', []);
};

exports.dispose = function (success, error) {
    exec(success, error, SERVICE, 'dispose', []);
};

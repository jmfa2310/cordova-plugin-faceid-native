var exec = require('cordova/exec');
var SERVICE = 'FaceIDPlugin';

exports.isAvailable = function (success, error) {
    exec(success, error, SERVICE, 'isAvailable', []);
};

exports.createDescriptor = function (imageBase64, success, error) {
    exec(success, error, SERVICE, 'createDescriptor', [imageBase64]);
};

exports.setEmployees = function (employeesJson, success, error) {
    exec(success, error, SERVICE, 'setEmployees', [employeesJson]);
};

exports.findBestMatch = function (imageBase64, threshold, minGap, success, error) {
    exec(success, error, SERVICE, 'findBestMatch',
        [imageBase64, threshold, minGap]);
};

exports.captureAndMatch = function (threshold, minGap, success, error) {
    exec(success, error, SERVICE, 'captureAndMatch',
        [threshold, minGap]);
};

exports.clearEmployees = function (success, error) {
    exec(success, error, SERVICE, 'clearEmployees', []);
};

exports.dispose = function (success, error) {
    exec(success, error, SERVICE, 'dispose', []);
};

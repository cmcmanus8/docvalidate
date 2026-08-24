// Proves the CJS half resolves under require(), which is the half that usually breaks.
const { DocValidateClient, ValidationTimeoutError } = require('../dist/index.cjs');

if (typeof DocValidateClient !== 'function') throw new Error('CJS: DocValidateClient missing');
if (typeof ValidationTimeoutError !== 'function') throw new Error('CJS: error type missing');
console.log('CJS  require ok');

// Proves the ESM half of the build resolves and exports what it claims.
import { DocValidateClient, ValidationTimeoutError } from '../dist/index.js';

if (typeof DocValidateClient !== 'function') throw new Error('ESM: DocValidateClient missing');
if (typeof ValidationTimeoutError !== 'function') throw new Error('ESM: error type missing');
console.log('ESM  import ok');

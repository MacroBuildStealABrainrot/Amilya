import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';
export default tseslint.config(
 {ignores:['dist/**','node_modules/**']},
 {files:['src/**/*.{ts,tsx}'],extends:[js.configs.recommended,...tseslint.configs.recommended],languageOptions:{globals:{...globals.browser,...globals.node}},rules:{'no-empty':['error',{allowEmptyCatch:true}],'@typescript-eslint/no-unused-vars':['error',{argsIgnorePattern:'^_'}]}},
 {files:['public/sw.js'],...js.configs.recommended,languageOptions:{globals:globals.serviceworker}}
);

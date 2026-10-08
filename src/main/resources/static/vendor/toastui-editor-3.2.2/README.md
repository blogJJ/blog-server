# Toast UI Editor 3.2.2 (D-74)

CSP(script-src 'self')를 지키려고 CDN 대신 우리 서버에서 내보낸다.

- `toastui-editor-all.min.js`: `@toast-ui/editor@3.2.2`와 한국어 언어 파일, prosemirror 의존성을 esbuild로 한 파일(IIFE)로 묶은 것. `window.toastui.Editor`로 쓴다.
- `toastui-editor.css`: 패키지의 `dist/toastui-editor.css` 그대로.
- 라이선스: Toast UI Editor와 prosemirror는 MIT, 함께 묶인 DOMPurify는 Apache 2.0 또는 MPL 2.0. 각 라이선스 문구는 js 파일 끝에 들어 있다.

다시 만드는 방법:

```bash
npm install @toast-ui/editor@3.2.2 esbuild@0.24.0
cat > entry.js <<'JS'
import Editor from '@toast-ui/editor';
import '@toast-ui/editor/dist/i18n/ko-kr';
window.toastui = window.toastui || {};
window.toastui.Editor = Editor;
JS
npx esbuild entry.js --bundle --minify --format=iife --target=es2018 --legal-comments=eof --outfile=toastui-editor-all.min.js
```

# 구글·깃허브 소셜 로그인 설정 (T157, D-128)

로그인·회원가입 화면의 "Google로 계속하기", "GitHub로 계속하기" 버튼은 키를 넣어야 켜집니다. 키가 없는 제공자는 버튼이 숨고, 서버는 키가 하나도 없어도 그대로 뜹니다. 구글만, 깃허브만 먼저 켜도 됩니다.

아래 콘솔 화면의 메뉴 이름과 버튼 문구는 시기에 따라 조금 다를 수 있습니다. 비슷한 이름을 찾아 진행하면 됩니다.

## 돌아오는 주소 (Redirect URI)

| 제공자 | 운영 주소 | 로컬 시험 주소 |
| --- | --- | --- |
| 구글 | `http://jj-blog.java21.net/login/oauth2/code/google` | `http://localhost:8080/login/oauth2/code/google` |
| 깃허브 | `http://jj-blog.java21.net/login/oauth2/code/github` | `http://localhost:8080/login/oauth2/code/github` |

- 서버는 이 주소를 요청 주소로 만듭니다. 운영에서는 nginx가 보낸 `Host`와 `X-Forwarded-Proto`를 따릅니다. 나중에 사이트를 https로 바꾸면 `https://jj-blog.java21.net/...`도 콘솔에 등록해야 합니다.
- 로그인할 때 구글이 `redirect_uri_mismatch`, 깃허브가 "The redirect_uri is not associated with this application"이라고 하면, 오류 화면이나 주소창의 `redirect_uri=` 값과 콘솔에 넣은 주소가 글자 하나까지(http/https, 끝의 `/`) 같은지 확인합니다.

## 1. 구글 OAuth 클라이언트 만들기

1. [Google Cloud Console](https://console.cloud.google.com/)에 로그인하고 위쪽에서 프로젝트를 고르거나 새로 만듭니다(예: `jj-blog`).
2. **API 및 서비스 → OAuth 동의 화면**(새 화면에서는 **Google 인증 플랫폼 → 브랜딩/대상**)으로 갑니다.
   - 사용자 유형: **외부**
   - 앱 이름(예: JJ 블로그), 사용자 지원 이메일, 개발자 연락처 이메일을 넣습니다.
   - 범위(데이터 액세스)는 기본 `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`만 있으면 됩니다. 민감한 범위가 아니라 구글 심사는 필요 없습니다.
   - 게시 상태가 **테스트**이면 "테스트 사용자"에 넣은 계정만 로그인됩니다. 모두가 쓰게 하려면 **앱 게시(프로덕션으로 푸시)**를 누릅니다.
3. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**(새 화면에서는 **클라이언트 → 클라이언트 만들기**)를 고릅니다.
   - 애플리케이션 유형: **웹 애플리케이션**
   - 이름: 아무거나(예: jj-blog-web)
   - **승인된 리디렉션 URI**에 `http://jj-blog.java21.net/login/oauth2/code/google`을 넣습니다. 로컬에서도 시험하려면 `http://localhost:8080/login/oauth2/code/google`을 하나 더 넣습니다.
   - "승인된 JavaScript 원본"은 비워 둬도 됩니다.
4. 만들면 **클라이언트 ID**와 **클라이언트 보안 비밀번호(Client secret)**가 나옵니다. 이 두 값을 아래 3단계의 secrets에 넣습니다. 보안 비밀번호는 나중에 다시 못 볼 수 있으니 바로 옮겨 둡니다.

## 2. 깃허브 OAuth App 만들기

1. 깃허브 오른쪽 위 프로필 → **Settings → Developer settings → OAuth Apps → New OAuth App**으로 갑니다(조직 소유로 만들려면 조직의 Settings → Developer settings).
   - GitHub App이 아니라 **OAuth App**입니다.
2. 값을 넣습니다.
   - Application name: 예) JJ 블로그
   - Homepage URL: `http://jj-blog.java21.net`
   - **Authorization callback URL**: `http://jj-blog.java21.net/login/oauth2/code/github`
   - Enable Device Flow는 체크하지 않습니다.
3. **Register application**을 누르면 **Client ID**가 보입니다. **Generate a new client secret**을 눌러 비밀값을 만들고 바로 복사합니다(다시 볼 수 없습니다).
4. OAuth App에는 callback URL을 하나만 넣을 수 있어서, 로컬에서 시험하려면 callback을 `http://localhost:8080/login/oauth2/code/github`로 한 OAuth App을 따로 하나 더 만듭니다.
5. 서버는 로그인할 때 `read:user`, `user:email` 권한을 요청합니다. 깃허브 계정의 대표(primary) 이메일이 인증돼 있어야 기존 계정과 연결하거나 새로 가입할 수 있습니다.

## 3. 저장소 secrets 넣기

blog-server 저장소 **Settings → Secrets and variables → Actions → New repository secret**에서 넣습니다. 모두 선택 항목이고, 아이디와 비밀값을 둘 다 넣은 제공자만 켜집니다.

| 이름 | 값 |
| --- | --- |
| `GOOGLE_CLIENT_ID` | 구글 클라이언트 ID (`...apps.googleusercontent.com`) |
| `GOOGLE_CLIENT_SECRET` | 구글 클라이언트 보안 비밀번호 |
| `GH_CLIENT_ID` | 깃허브 OAuth App의 Client ID |
| `GH_CLIENT_SECRET` | 깃허브 OAuth App의 Client secret |

깃허브 secret 이름은 `GITHUB_`로 시작할 수 없어서 깃허브 키는 `GH_`로 시작합니다. 서버 환경변수 이름도 같습니다.

넣은 뒤 Actions 탭 → Deploy → **Run workflow**로 다시 배포하면(또는 다음 main 배포 때) 버튼이 나타납니다. 값은 저장소 파일이나 이슈·PR에 적지 않습니다. 로컬에서는 저장소 루트의 `.env`에 같은 이름으로 넣습니다(`.env.example` 참고).

## 동작 정리

- **이미 연결된 소셜 계정**: 그 회원으로 로그인합니다. 일반 로그인과 같은 로그인 쿠키(30분 또는 "로그인 유지" 14일)를 씁니다.
- **처음 쓰는 소셜 계정**: 구글·깃허브가 **인증한** 이메일과 같은 이메일로 가입된 회원(탈퇴 제외)이 있으면 그 계정에 연결하고 로그인합니다. 인증 안 된 이메일로는 연결하지 않습니다.
- **가입된 계정이 없으면**: 가입 마무리 화면(`/social-signup.html`)에서 이름(미리 채움)·닉네임·필수 약관 동의만 받고 가입합니다. 비밀번호와 전화번호는 받지 않습니다. 10분 안에 마쳐야 합니다.
- **비밀번호 없는 회원**: 이메일·비밀번호 로그인은 비밀번호가 틀린 것과 같게 실패합니다. 내 정보의 "비밀번호 만들기"나 비밀번호 찾기로 비밀번호를 만들 수 있습니다. 비밀번호가 없으면 마지막 소셜 연결은 해제할 수 없습니다.
- **내 정보 → 소셜 로그인**에서 연결·해제합니다. 로그인 잠금(5번 실패)은 소셜 로그인도 막고, 계정 정지 중이면 일반 로그인처럼 로그인은 되고 읽기만 됩니다.

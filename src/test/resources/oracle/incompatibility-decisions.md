# Oracle非互換67ケースの対応判断

基準コミット118260dで発見した全67ケースを、ケース単位で評価した。
前回18件・P1の10件に加え、P2の16件を対応した。P3再評価で推奨した2件も実装し、合計46件対応済み、21件未対応。元のP3の20件は対応済み2件・条件付き候補5件・見送り維持13件となった。P2の3件は見送りを維持する。全件実装は目標にしていない。
実利用のSQLログは未提供のため、頻度は一般的な業務用途からの見込みであり実測統計ではない。
「見送り」は互換性の承認ではなく既知の制限。sameをrejectへ変更して合格扱いにはしない。
実装対象の再現SQLと境界条件は、既存のscalar/*.tomlとquery/semantics.tomlに追加した。
「対応済み」は記載SQLと追加した境界条件に対する結果であり、その関数の全形式・全型・全NLS設定の互換性を主張しない。

元の記録は保存ブランチfix/prioritized-oracle-compatibilityのコミットbabbd74にある。
この表には67件すべてのID、再現SQL、判断と理由を収録し、handoverディレクトリへの実行時依存はない。

## 対応優先度

優先度は不具合の重大度ではなく、次に実装する順序を表す。実利用SQLの統計はないため、頻度の見込み、影響範囲、仕様を安全に固定できるか、保守コストを合わせて判断した。

- **P0（対応済み）**: 18件。回帰テストを既存のscalar/*.tomlに追加し、Oracle 18c XEとの差分を解消済み。
- **P1（対応済み）**: 10件。利用頻度が高く、SQLの結果や型に直接影響する。既存の型判定と日時・数値ランタイムを拡張して対応した。対象範囲と制限は後述。
- **P2（今回判断済み）**: 19件中16件を対応。3件は追加のOracle実測と保守コストから見送り、明示拒否にした。対応範囲・理由は後述。
- **当初P3（再評価・2件対応済み）**: 20件。対応済み2件・条件付き候補5件・見送り維持13件。P1/P2で下がった追加コストと、不正SQLの実行防止を評価し直し、推奨した2件を実装した。実装と検証は末尾に記載。

### P0（対応済み、18件）

`mod-zero`, `to-number-group-separator`, `replace-omitted-replacement`, `ceil-character`, `to-char-small-decimal`, `translate-delete-all`, `mod-character`, `to-number-nan`, `to-number-hex`, `to-char-format-overflow`, `to-char-format-negative-zero`, `coalesce-single-argument`, `replace-null-search`, `replace-null-replacement`, `upper-number`, `add-months-fraction`, `rpad-fractional-length`, `nullif-null-first`

### P1（対応済み、10件）

`number-division`, `division-decimal`, `length-fixed-char`, `nullif-type-check`, `date-subtraction`, `timestamp-cast-date`, `to-date-trailing-data`, `union-mixed-types`, `coalesce-mixed-types`, `char-varchar-equality`

P1は、金額・比率計算、NULL処理、日付差分、文字列比較など業務SQLで使われやすい領域を優先した。JDBCメタデータと既存の列型判定を共有し、値だけでなく型・エラーも検証した。P1の番号は当初の優先順位を保持している。

### P2（16件対応済み・3件見送り、計19件）

`number-overflow`, `number-underflow`, `number-cast-rounding`, `sqrt-negative`, `date-invalid-day`, `fetch-fraction`, `stddev-single-row`, `nvl-eager-error`, `union-char-padding`, `fetch-percent`, `fetch-null`, `date-extract-hour`, `timestamp-date-difference`, `remainder-ties`, `cast-varchar-overlength`, `next-day`, `date-trunc-century`, `fetch-negative`, `sequence-repeated-nextval`

P2はP1の数値・型・日時処理を再利用して対応した。`stddev-single-row`、`fetch-percent`、`timestamp-date-difference`の3件は、一般的な境界条件まで保証するための追加設計が大きく、今回見送る。単一の再現SQLだけを通す近似や許容誤差は追加しない。

### 当初P3（再評価対象、20件）

`ascii-unicode`, `lengthb-unicode`, `substrb-ascii`, `instrb-unicode`, `chr-zero`, `initcap-word-boundary`, `lag-ignore-nulls`, `ratio-to-report`, `median`, `soundex`, `nanvl`, `dump-number`, `to-single-byte`, `to-multi-byte`, `unistr`, `hextoraw`, `to-date-year-zero`, `percentile-cont`, `keep-dense-rank`, `sequence-in-where`

当初はこれらを一括して見送ったが、現在はP1/P2の共通処理を再利用できる。文字セット依存という理由だけでは一律見送りにせず、対応環境を明示して小さく実装できるかを検討する。再評価の内訳・着手条件は末尾に記載した。

実利用SQLは用途依存機能の判断材料とするが、不正SQLの副作用防止にまで実利用実績を必須とはしない。対応範囲、対象文字セット、列・バインド型、JDBCメタデータとOracle 18c XEの境界検証を満たしたものから実装する。

| 発見ID（main-gap.省略） | 判断 | 頻度見込み | 保守負担 | 理由 | 再現SQL |
|---|---|---|---|---|---|
| `mod-zero` | 前回対応済み | 中 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT MOD(7, 0) AS value FROM dual` |
| `number-division` | P1対応済み | 高 | 大 | 共通のNUMBER除算で整数除算を防止し、base-100で20桁の商を整数商・剰余から丸める。列・符号・入れ子・バインド・ゼロ除算を検証。 | `SELECT 1 / 3 AS value FROM dual` |
| `number-overflow` | P2対応済み | 中 | 大 | 乗算結果と桁数未指定NUMBER CASTを共通ランタイムで正規化し、指数超過をORA-01426にする。加減算・集約を含むNUMBER演算全体の範囲保証ではない。 | `SELECT 1e125 * 1e125 AS value FROM dual` |
| `to-number-group-separator` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('1,234') AS value FROM dual` |
| `length-fixed-char` | P1対応済み | 中 | 中 | CHARの空白を保持する文字列変換とLENGTHオーバーロードで対応。CASTの既定BYTE長、日本語、NULL、派生表・物理列も検証。 | `SELECT LENGTH(CAST('A' AS CHAR(3))) AS value FROM dual` |
| `replace-omitted-replacement` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT CASE WHEN REPLACE('a', 'a') IS NULL THEN 1 ELSE 0 END AS value FROM dual` |
| `nullif-type-check` | P1対応済み | 高 | 中 | 既存の型判定でNUMBERと文字型の混在をORA-00932にする。型未指定バインドはFUNCTION_BIND_TYPEで拒否し、明示CASTを要求。 | `SELECT NULLIF(1, '1') AS value FROM dual` |
| `date-subtraction` | P1対応済み | 高 | 中 | DATE型判定を値の変換とJDBCメタデータで共有し、日単位のNUMBERとscale 0を返す。時刻を含む式と物理DATE列も検証。 | `SELECT DATE '2024-03-02' - DATE '2024-03-01' AS value FROM dual` |
| `timestamp-cast-date` | P1対応済み | 高 | 大 | 秒未満を切り捨てる日時ランタイムを使い、年月日と時分秒を保持。日末の端数、NULL、TIMESTAMPバインドを検証。 | `SELECT CAST(TIMESTAMP '2024-01-02 03:04:05.123456' AS DATE) AS value FROM dual` |
| `sequence-repeated-nextval` | P2対応済み | 中 | 大 | 通常SELECTの内側にvolatileな採番列を置き、対象行ごとに一度だけ評価する。複数行・空結果・CURRVAL同時参照・バインド順を検証。重複参照のDML・集約等は明示拒否。 | `SELECT bs_seq.NEXTVAL AS first_value, bs_seq.NEXTVAL AS second_value FROM dual` |
| `number-underflow` | P2対応済み | 中 | 大 | 共通NUMBER正規化で絶対値1e-130未満を0にする。乗算の境界、負数、バインドを検証。 | `SELECT 1e-130 * 1e-130 AS value FROM dual` |
| `sqrt-negative` | P2対応済み | 低 | 小～中 | 専用SQRTでORA-01428/72000を返す。正数は十分な小数桁で計算してNUMBER正規化し、0・2・4・NULLも比較。 | `SELECT SQRT(-1) AS value FROM dual` |
| `ceil-character` | 前回対応済み | 中 | 小 | 既存の数値引数変換を再利用し、NUMBER結果とメタデータを保証する。 | `SELECT CEIL('1.2') AS value FROM dual` |
| `to-char-small-decimal` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(0.1) AS value FROM dual` |
| `ascii-unicode` | 条件付き候補（未実装） | 用途依存 | 中 | コードポイントとDB文字セットのバイト表現の違い。UTF-8固定の特例を増やさず文字セット要件を確認する。 | `SELECT ASCII('日') AS value FROM dual` |
| `translate-delete-all` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT CASE WHEN TRANSLATE('a', 'xa', 'x') IS NULL THEN 1 ELSE 0 END AS value FROM dual` |
| `to-date-trailing-data` | P1対応済み | 高 | 大 | 既存の数値日付書式の入力消費範囲を検査し、余剰入力をORA-01830/22008にする。可変桁・区切り省略と既存FXケースを維持。 | `SELECT TO_DATE('2024-01-02garbage', 'YYYY-MM-DD') AS value FROM dual` |
| `union-mixed-types` | P1対応済み | 中 | 大 | 集合演算のNUMBERと文字型の暗黙変換をやめORA-01790を返す。NULLの数値型合わせは維持。 | `SELECT 1 AS value FROM dual UNION ALL SELECT '1' AS value FROM dual` |
| `fetch-fraction` | P2対応済み | 低～中 | 中 | FETCH用の共通行数処理で小数を切り捨てる。OFFSETとの併用、バインド、集合演算、WITH TIESを検証。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST 1.9 ROWS ONLY` |
| `stddev-single-row` | 見送り（P2） | 用途依存 | 中 | 単一行だけの定数書き換えではなく、集約・ウィンドウとNULL行数を統一した実装が必要。 | `SELECT STDDEV(1) AS value FROM dual` |
| `division-decimal` | P1対応済み | 高 | 大 | NUMBER除算の共通ランタイムで小数の桁落ちを解消。定数式と列・型付きNULL・バインドのJDBC scaleも検証。 | `SELECT 1.0 / 7 AS value FROM dual` |
| `number-cast-rounding` | P2対応済み | 中 | 大 | 桁数未指定CASTにP1と同じbase-100で20桁の正規化を適用。長い正負の値、NULL、バインド、scale -127を検証。 | `SELECT CAST(1.234567890123456789012345678901234567890123456789 AS NUMBER) AS value FROM dual` |
| `mod-character` | 前回対応済み | 中 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT MOD('7','3') AS value FROM dual` |
| `to-number-nan` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('NaN') AS value FROM dual` |
| `to-number-hex` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('0x10') AS value FROM dual` |
| `to-char-format-overflow` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(12345, '999') AS value FROM dual` |
| `to-char-format-negative-zero` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(-0.01, '9') AS value FROM dual` |
| `coalesce-mixed-types` | P1対応済み | 高 | 中 | NUMBERと文字型の混在をORA-00932にする。COALESCE自体は維持して短絡評価を保持。バインドは明示CASTを要求。 | `SELECT COALESCE('1', 2) AS value FROM dual` |
| `coalesce-single-argument` | 前回対応済み | 中 | 小 | ASTから引数不足／NULLリテラルを確定できる。専用エラーコードをOracle JDBCエラーへ対応付ける。 | `SELECT COALESCE(1) AS value FROM dual` |
| `nvl-eager-error` | P2対応済み | 低 | 中 | 元の定数式はP1で一致済み。SQL関数のインライン化を防ぎ、列・バインドのfallbackも必ず評価する。エラー後の再実行も確認。 | `SELECT NVL(1, 1 / 0) AS value FROM dual` |
| `lengthb-unicode` | 条件付き候補（未実装） | 用途依存 | 中 | DB文字セットとCHARの空白を含むバイト数を定義する必要がある。 | `SELECT LENGTHB('日本') AS value FROM dual` |
| `substrb-ascii` | 見送り（P3） | 用途依存 | 大 | ASCIIだけなら容易だがマルチバイトの途中切断時のOracle規則が必要。関数名だけ通す対応はしない。 | `SELECT SUBSTRB('abcdef', 2, 3) AS value FROM dual` |
| `instrb-unicode` | 条件付き候補（未実装） | 用途依存 | 中 | バイト位置、負の開始位置、出現回数を文字セットと合わせて検証する必要がある。 | `SELECT INSTRB('日本語', '本') AS value FROM dual` |
| `chr-zero` | 見送り（P3） | 低 | 大 | PostgreSQL textはNULを保持できないため、文字列の表現を変える必要がある。 | `SELECT LENGTH(CHR(0)) AS value FROM dual` |
| `initcap-word-boundary` | 条件付き候補（未実装） | 低～中 | 中 | 値は一致しており差分はJDBC precision。言語ごとの大文字化による長さの変化まで考慮する必要がある。 | `SELECT INITCAP('abc_def 123abc') AS value FROM dual` |
| `replace-null-search` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT REPLACE('abc', NULL, 'x') AS value FROM dual` |
| `replace-null-replacement` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT REPLACE('aba', 'a', NULL) AS value FROM dual` |
| `upper-number` | 前回対応済み | 中 | 小 | 数値と分かる引数だけ既存の文字列変換を適用する。階層SQLが使うUPPERの構造は保持する。 | `SELECT UPPER(123) AS value FROM dual` |
| `char-varchar-equality` | P1対応済み | 高 | 大 | 両側の型がCHARとVARCHAR2の場合、CHARの末尾空白を保持したtext比較へ変換。左右反転・末尾空白・物理列も検証。 | `SELECT CASE WHEN CAST('A' AS CHAR(3)) = CAST('A' AS VARCHAR2(3)) THEN 1 ELSE 0 END AS value FROM dual` |
| `date-invalid-day` | P2対応済み | 中 | 大 | YYYY-MM-DDとFXYYYY-MM-DDの月内日付を検査しORA-01839にする。閏年・非閏年・4月末を検証。他書式のエラー優先順位は拡張しない。 | `SELECT TO_DATE('2024-02-30', 'YYYY-MM-DD') AS value FROM dual` |
| `add-months-fraction` | 前回対応済み | 中 | 小 | 月数の小数部を切り捨て、既存の日付計算に委譲できる。 | `SELECT ADD_MONTHS(DATE '2024-01-15', 1.9) AS value FROM dual` |
| `date-extract-hour` | P2対応済み | 低 | 中 | P1のDATE型判定を再利用して不正フィールドをORA-30076にする。CAST DATEのMINUTEとTIMESTAMPのHOURも検証。 | `SELECT EXTRACT(HOUR FROM DATE '2024-01-02') AS value FROM dual` |
| `timestamp-date-difference` | 見送り（P2） | 中 | 大 | INTERVALの値・JDBC型・精度まで保証する日時演算の設計が必要。 | `SELECT TIMESTAMP '2024-01-02 01:00:00' - DATE '2024-01-01' AS value FROM dual` |
| `union-char-padding` | P2対応済み | 中 | 大 | 元のCAST幅から集合演算の文字型・最大幅を決定し、幅が異なるCHARは末尾空白を保持したtextで集合演算する。同幅CHARとVARCHAR2混在も検証。 | `SELECT CAST('A' AS CHAR(2)) AS value FROM dual UNION SELECT CAST('A' AS CHAR(3)) AS value FROM dual` |
| `fetch-percent` | 見送り（P2） | 低 | 大 | 総行数と端数処理が必要で、単純なLIMIT変換では表現できない。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST 10 PERCENT ROWS ONLY` |
| `fetch-null` | P2対応済み | 低～中 | 中 | FETCHのNULLは0行とし、OFFSETのNULL規則と区別。型付きNULLバインドの再設定も検証。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST NULL ROWS ONLY` |
| `lag-ignore-nulls` | 見送り（P3） | 用途依存 | 大 | ウィンドウのフレーム・順序・NULL飛ばしを正確に実装する必要がある。 | `SELECT empno, LAG(note) IGNORE NULLS OVER (ORDER BY empno) AS value FROM bs_emp ORDER BY empno` |
| `ratio-to-report` | 条件付き候補（未実装） | 用途依存 | 大 | ウィンドウとNUMBER除算精度の両方に依存する。 | `SELECT empno, RATIO_TO_REPORT(sal) OVER () AS value FROM bs_emp ORDER BY empno` |
| `median` | 見送り（P3） | 用途依存 | 中～大 | NULL、偶数行、小数精度、集約型を検証する必要があり、doubleのpercentileへの置換では不十分。 | `SELECT MEDIAN(sal) AS value FROM bs_emp` |
| `remainder-ties` | P2対応済み | 低 | 中 | 整数商と剰余を使い、ちょうど半分では偶数商を選ぶ。符号・小数・NULL・ゼロ除算を検証。浮動小数型の優先順位全般は対象外。 | `SELECT REMAINDER(7, 2) AS value FROM dual` |
| `cast-varchar-overlength` | P2対応済み | 中 | 中 | P1時点で元のSQLが一致済み。末尾空白・NULLを含め、既存のVARCHAR2 CASTをOracle比較する回帰ケースとして固定。 | `SELECT CAST('abcd' AS VARCHAR2(2)) AS value FROM dual` |
| `rpad-fractional-length` | 前回対応済み | 中 | 小 | SQL runtimeは既に切り捨てている。LPAD/RPAD共通のリテラル幅推論だけを修正する。 | `SELECT RPAD('a',3.9,'x') AS value FROM dual` |
| `soundex` | 見送り（P3） | 用途依存 | 中 | 発音コード生成規則と非ASCII入力の定義が必要。利用SQLが確認できた場合に対象とする。 | `SELECT SOUNDEX('Smith') AS value FROM dual` |
| `nanvl` | 見送り（P3） | 低 | 大 | BINARY_FLOAT/BINARY_DOUBLEのNaNと型優先順位の対応が前提となる。 | `SELECT NANVL(1,2) AS value FROM dual` |
| `dump-number` | 見送り（P3） | 低 | 大 | Oracleの内部表現を再現する診断機能であり、通常の業務値の互換性より優先度が低い。 | `SELECT DUMP(123) AS value FROM dual` |
| `to-single-byte` | 見送り（P3） | 用途依存 | 大 | DB文字セット別の全角・半角変換表が必要となる。 | `SELECT TO_SINGLE_BYTE('ＡＢＣ') AS value FROM dual` |
| `to-multi-byte` | 見送り（P3） | 用途依存 | 大 | DB文字セット別の変換表と文字列幅の規則が必要となる。 | `SELECT TO_MULTI_BYTE('ABC') AS value FROM dual` |
| `unistr` | 見送り（P3） | 用途依存 | 中 | NVARCHAR2型と国別文字セット、サロゲート表現まで含むJDBC型契約が必要。 | `SELECT UNISTR('\0041') AS value FROM dual` |
| `hextoraw` | 見送り（P3） | 用途依存 | 中 | RAW値、型、長さとRAWTOHEXの文字列メタデータを一緒に検証する必要がある。 | `SELECT RAWTOHEX(HEXTORAW('abcd')) AS value FROM dual` |
| `next-day` | P2対応済み | 中 | 中 | 既定の英語曜日名で翌週・曜日略称・時刻保持・NULL・不正曜日を対応。他のNLS_DATE_LANGUAGEへの対応ではない。 | `SELECT NEXT_DAY(DATE '2024-01-01','MONDAY') AS value FROM dual` |
| `date-trunc-century` | P2対応済み | 低 | 中 | CC/SCCの西暦世紀境界を対応。1900/1901/2000/2001/西暦1年を検証。紀元前は明示拒否。 | `SELECT TRUNC(DATE '2000-06-01','CC') AS value FROM dual` |
| `to-date-year-zero` | P3対応済み | 低～中 | 大 | YYYY-MM-DD/FXYYYY-MM-DDのハイフン区切りで年0を検査。月日範囲のエラーを優先し、年0は暦妥当性・余剰入力より先にORA-01841。非FXの8桁年0入力はOracle実測に合わせORA-01861。 | `SELECT TO_DATE('0000-01-01','YYYY-MM-DD') AS value FROM dual` |
| `nullif-null-first` | 前回対応済み | 中 | 小 | ASTから引数不足／NULLリテラルを確定できる。専用エラーコードをOracle JDBCエラーへ対応付ける。 | `SELECT NULLIF(NULL,1) AS value FROM dual` |
| `fetch-negative` | P2対応済み | 低 | 中 | FETCHの共通処理で負数を0行にする。バインド再設定も検証。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST -1 ROWS ONLY` |
| `percentile-cont` | 見送り（P3） | 用途依存 | 大 | JDBC型の違いだけでなくdoubleによる補間精度も検証する必要がある。 | `SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY sal) AS value FROM bs_emp` |
| `keep-dense-rank` | 見送り（P3） | 用途依存 | 大 | 順位の同値行と集約・NULL処理を保持する構造書き換えが必要。 | `SELECT MAX(ename) KEEP (DENSE_RANK FIRST ORDER BY sal) AS value FROM bs_emp` |
| `sequence-in-where` | P3対応済み | 低 | 中 | SELECT WHEREの擬似列を重複参照と独立に実行前検査し、ORA-02287/42000を返す。既存の列型解決で通常のNEXTVAL列を区別。ネスト・CTE・到達不能条件・採番非消費・正常DMLを検証。 | `SELECT empno FROM bs_emp WHERE bs_seq.NEXTVAL > 0` |


## 前回の実装と回帰ケース

今回の13件は以下の既存ファイルで常時検証する。新たな回帰TOMLのローダーやhandoverへの依存は追加していない。

- [scalar/string.toml](cases/scalar/string.toml): REPLACE 3件、TRANSLATE 1件、TO_CHAR 3件、UPPER 1件、RPAD 1件。
- [scalar/number.toml](cases/scalar/number.toml): CEIL 1件。前回修正した5件もこのファイルにある。
- [scalar/core.toml](cases/scalar/core.toml): COALESCEの引数不足とNULLIFの第1引数NULL。
- [scalar/datetime.toml](cases/scalar/datetime.toml): ADD_MONTHSの小数月数。

上記13件と、NULL・符号・バインド・Unicode・メタデータなどの境界21件を合わせ、今回34ケースを追加した。
NULLIFの型検査全般とCOALESCEの混在型は見送り、ASTだけで確定できる不正引数を対象にした。
UPPERの数値変換は数値と分かる引数に限定し、階層SQLの既存UPPER構造を保持する。
非ASCII文字列の大文字化後の幅を入力の幅から推測する規則は追加していない。

[Oracle 18c REPLACE](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/REPLACE.html)、
[Oracle 18c TRANSLATE](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/TRANSLATE.html)
の規則を参照し、結論はOracle 18c XEの実測で確認した。

## 前回のTDDと検証の経過

1. 修正前に13件を既存TOMLへ追加し、Oracle差分365件中その13件だけが失敗することを確認した。
2. 文字列のローカルテストは12件中7件失敗・3件エラーだった。値、SQL NULL、JDBC precisionを確認してから修正し、12件をGreenにした。
3. 共通SQL runtime、OracleCoercion、既存のメタデータ推論を再利用した。別のパーサや新しいテスト実行機構は追加していない。
4. UPPERの書き換えが階層SQL4件を拒否する回帰を検出した。数値と判明する引数だけを変換し、既存階層SQLを維持する形に修正した。
5. Oracle差分で境界ケースの値とメタデータを比較し、Maven verifyで全ローカルテスト・書式・runtimeパッケージ・JAR内容を検証した。

書式付きTO_CHARを4つ連結する追加探索では、値は一致したが、連結結果のJDBC precisionに19 / 2147483647の既存差分を発見した。
これは67件の一覧外で、書式関数の値の修正とは別の文字列幅の合成問題として未対応。
最終の書式テストは4つの結果を別々の列として取得し、それぞれの値とメタデータを厳密比較する。連結結果の互換性は主張しない。

再現SQL: SELECT TO_CHAR(-0.01, '999') || '/' || TO_CHAR(-0.01, '000') || '/' || TO_CHAR(-12, '999') || '/' || TO_CHAR(12, '999') AS value FROM dual

探索ログ: /tmp/orafit-review-oracle-red.log、/tmp/orafit-review-scalar-red.log、/tmp/orafit-review-oracle-green-1.log。

## 前回の検証結果（2026-09-06）

- ./mvnw verify: 547件成功、Failures 0、Errors 0、Skipped 0。書式、runtimeパッケージ生成、JAR内容検証も成功。
- ./.github/workflows/ci.sh: Oracle 18c XE 18.4.0.0.0対embedded PostgreSQL 17.10、386件成功、Failures 0、Errors 0、Skipped 0、終了コード0。
- 今回の製品コード差分: 6ファイル、追加115行・削除6行。テストと判断表はこの行数に含めない。
- 最終ログ: /tmp/orafit-review-verify-final.log、/tmp/orafit-review-oracle-final-2.log。
- 合計18件対応済み（前回5件＋今回13件）。49件は上表の理由で今回見送り。

## P1の実装と検証（2026-09-06）

既存のscalar/core.toml・number.toml・string.toml・datetime.tomlとquery/semantics.tomlに、P1の再現10件と境界ケースを追加した。handoverへの依存はない。

実装は既存のScalarLoweringRule、DatabaseTypeCoercionRule、ResultMetadataPlannerとextension/srcの数値・日時・文字列モジュールを拡張した。SQLの再解析、独立した型推論器、別のテストローダーは追加していない。NUMBERの除算は固定小数桁へのCASTではなく、整数商と剰余から丸める。

一時テーブルのCHAR比較で、列型解決がcurrent_schemaだけを参照していた問題を検出した。PostgreSQLのto_regclassで実際に見えるスキーマを取得し、既存の列メタデータ取得に渡す。型判定が必要な翻訳には、このカタログ問い合わせのコストが加わる。

### 対応範囲

- 元のP1の10ケースはすべてsameの厳密比較を維持する。既存の失敗ケースをrejectへ変更していない。
- NULLIF/COALESCEの数値・文字混在を型判定する。型未指定の直接バインド引数はFUNCTION_BIND_TYPEで拒否し、CAST(? AS NUMBER)など型を明示したSQLを受け付ける。この制限は新規のreject契約2件に記載した。
- CHARの長さ超過CASTは近似せず0A000で拒否する。文字セット・NLS全般やCHARのあらゆる暗黙変換をサポートする主張ではない。
- TO_DATEは既存の数値日付書式の範囲で入力末尾を検証する。月名、言語設定、無効日付のOracleエラー順全般は対象外。
- TIMESTAMP→DATEのCASTとDATE同士の減算を対象とする。Oracle DATEのDDL型マッピング全般やTIMESTAMPとの混在減算は別課題。
- NUMBERの他の演算・桁数未指定CASTの丸めはP2のまま。除算で得た結果以外のNUMBER全体の互換性は主張しない。

### TDDの記録

1. P1の再現10件を先に追加し、Oracle差分396件中その10件だけが失敗することを確認した（/tmp/orafit-p1-red.log）。
2. 値を修正してから境界ケースを追加し、翻訳を通らない列除算、CHARのBYTE長、型付きNULLのメタデータを検出して修正した。413件すべてのOracle差分が成功した（/tmp/orafit-p1-oracle-2.log）。
3. PreparedStatementのバインド順、NULL再設定、ゼロ除算後の再実行、物理DATE列、一時テーブルのCHAR比較をローカルテストへ追加。一時テーブルの比較でRedを確認し、列型取得を修正した（/tmp/orafit-p1-bind-red.log）。
4. DATE列・TIMESTAMPバインド・型付きNULL関数のOracleケースと、型未指定バインドの明示reject契約を追加し、最終検証を行った。

### P1の最終結果

- `./mvnw verify`: 585件、Failures 0、Errors 0、Skipped 0。AOSP書式、runtimeパッケージ、JAR内容検証を含め成功。
- `./.github/workflows/ci.sh`: Oracle 18c XE 18.4.0.0.0対embedded PostgreSQL 17.10、421件、Failures 0、Errors 0、Skipped 0、終了コード0。
- 今回追加したTOMLは35ケース（元の再現10件＋境界25件）。このうち33件がsame、2件が型未指定バインドの明示reject。別途、ローカル統合テスト3件を追加した。
- 最終ログ: `/tmp/orafit-p1-verify-final.log`、`/tmp/orafit-p1-oracle-final.log`。
- 全67件中、28件対応済み（前回18件＋P1の10件）。P2の19件、P3の20件は今回の実装対象に含めない。

## P2の実装と検証（2026-09-06）

P1のコミットab6f780から`fix/oracle-p2-compatibility`を作成した。
元のP2の19件を既存のscalar/*.tomlとquery/semantics.tomlへ追加し、修正前のOracle差分440件中17件が失敗した（`/tmp/orafit-p2-red.log`）。
`nvl-eager-error`と`cast-varchar-overlength`の元のSQLはP1時点で一致していた。NVLは定数のエラーだけでは実行時の評価省略を検出できないため、列・バインドの追加検証が必要。

参照仕様: [Oracle 18c SELECT](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/SELECT.html)、[ROUND/TRUNC日付単位](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/ROUND-and-TRUNC-Date-Functions.html)。互換性の結論はドキュメントからの推測ではなくOracle 18c XEとの実測で確定する。

### P2で見送った3件

| 対象 | 実測・実装検討で判明した点 | 判断と必要な次の設計 |
|---|---|---|
| STDDEV | 単一行を0とする集約を試作したが、部署別の複数行で末尾桁がOracleと相違した。例: deptno=20はOracle `1588.500340992514025004316097125627664165`、試作 `1588.500340992514025004316097125627664162`。分析関数も精度・NULL・窓の契約が必要。 | 統計用途では複数行が本来の用途。単一行だけの修正を製品化せず試作集約を削除し、`STDDEV_PRECISION`で明示拒否。Oracleの中間NUMBER丸めまで固定する設計が必要。 |
| FETCH PERCENT | 固定件数のFETCHと違い、全選択行数と端数切上げが必要。WHERE・DISTINCT・集合演算・OFFSET・WITH TIES・バインド順を保持する構造変更が必要。 | 通常の件数ページングを優先。百分率専用の行数集計・ウィンドウ・投影変更を増やさず、`FETCH_PERCENT`で明示拒否。 |
| TIMESTAMPとDATEの混在減算 | 結果はNUMBERではなくOracle INTERVAL。SQL演算だけでなくJDBC型、精度、getString/getObject、符号と秒未満の表現が必要。 | 新しいJDBC値型を、この1ケースだけのために増やさない。`TIMESTAMP_DIFFERENCE`で明示拒否し、INTERVAL全体の契約設計に分離。 |

この3件の元の`same`ケースとSTDDEVの探索境界は[p2-deferred.toml](p2-deferred.toml)にそのまま保存した。`cases/`の外に置いた調査用ファイルであり、通常CIの互換性成功件数には含めない。
元ケースを`reject`へ書き換えて差分を合格扱いにしたものではない。通常CIには別IDの明示拒否契約4件（STDDEV集約/分析、FETCH PERCENT、日時混在減算）を登録し、これらは互換性の主張ではない。
追加調査を再現する場合は、このファイルを一時的に`cases/query/`へコピーしてci.shを実行し、実行後にコピーを削除する。厳密比較の失敗が期待結果となる。

### P2の対応範囲と保守性

- 16件は元のOracle期待結果を維持した厳密比較。既存のP0/P1ケースの期待値や許容誤差は変更していない。
- 乗算と桁数未指定NUMBER CASTは共通の`number_value`を使う。任意の加減算・列格納・集約までNUMBERの全制約を保証する変更ではない。
- NULLIF/COALESCEと同様、型が必要なEXTRACT・日時混在検査は既存の列型解決を使う。別のパーサや再解析を追加しない。
- NVLはPL/pgSQLの関数境界で引数評価を保持。COALESCEの短絡評価は維持する。
- シーケンスは通常SELECTの投影に限定する。DMLの重複NEXTVAL、集約・行数制限などを伴う重複参照は`SEQUENCE_PROJECTION`で拒否する。シーケンス自体の値を一時的に巻き戻す仕組みやセッションキャッシュは追加しない。
- CHAR集合演算で列幅を確定できない場合は`SET_CHAR_WIDTH`で拒否し、各枝の明示CASTを要求する。この新規拒否契約も通常CIに追加した。
- `handover/`への追加・依存はない。製品コードは既存の型/メタデータ/SQLランタイムを拡張し、行単位のシーケンス投影だけを独立した小さなルールにした。

### P2のTDD・最終検証

1. 元の19件を先に追加し、修正前は440件中17件失敗（`/tmp/orafit-p2-red.log`）。元のNVL定数式・VARCHAR2 CASTの2件はすでに一致していた。
2. 境界を含む478件で、STDDEVの中間精度、SQRTの桁数とNULLメタデータ、CHAR集合の重複排除、シーケンス式のscaleの差分を検出（`/tmp/orafit-p2-oracle-3.log`）。精度の近似や許容誤差を追加せず、STDDEVは見送った。
3. シーケンス名の大小文字混在をローカルテストでRedにし（`/tmp/orafit-p2-sequence-case-red.log`）、未引用名の正規化を共有した。さらにDMLのAST取得ではVALUES内の重複参照を拾えない経路を検出し、実際に式を処理するScalarLoweringRuleで拒否するよう修正した。
4. PreparedStatementのFETCHバインド・NULL再設定、シーケンスの消費数・空行・バインド順、NVLの引数評価とエラー後の再実行、シーケンスの非対応構文を統合テスト4件で確認した。

- `./mvnw verify`: **644件、Failures 0、Errors 0、Skipped 0**。AOSP書式、runtimeパッケージ生成、JAR内容も成功。ログ: `/tmp/orafit-p2-verify-final.log`。
- `./.github/workflows/ci.sh`: **Oracle 18c XE 18.4.0.0.0 対 embedded PostgreSQL 17.10、476件、Failures 0、Errors 0、Skipped 0、終了コード0**。ログ: `/tmp/orafit-p2-oracle-final.log`。
- 通常CIに追加したTOMLは**55件（same 50件、明示reject 5件）**。sameの内訳は元のP2再現16件と境界34件。rejectは成功互換性の主張に含めない。
- 調査用`p2-deferred.toml`は**9件**（見送りの元ケース3件とSTDDEVの境界6件）。通常CIから除外されていることを明記し、元のsame期待結果を保存した。
- 全67件の判断表は重複なし。**44件対応済み、23件見送り（P2 3件・P3 20件）**。


## 当初P3の再評価（2026-09-06、基準a4dc0d4）

**20件の一律見送りは撤回する。次期対応推奨2件、条件付き候補5件、見送り維持13件とする。**
この節は基準a4dc0d4での着手前の設計判断を記録したもの。再評価時点では対応済み44件だった。その後、推奨2件を実装した記録を次の節に記載する。過去の67件のID・再現SQL・検証結果とP0/P1/P2の実装記録は保持する。
頻度は実SQLログに基づく統計ではなく用途からの見込み。元のP3という分類は履歴として残す。

### 判断を変える根拠

- P2の`SequenceProjectionRule`には配置検査があるが、投影内に繰り返し参照がないと検査を抜ける経路がある。WHEREだけのNEXTVALを検査するには、採番回数の書き換えと独立した配置検査が必要。OracleはSELECTのWHEREでシーケンス擬似列を禁止しているため、実行前に検査する設計を優先する。[Oracle 18c Sequence Pseudocolumns](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/Sequence-Pseudocolumns.html)
- P2の`to_date`は既にYYYY-MM-DD/FXYYYY-MM-DDの年・月・日を取得する。年0については、その取得済みの年を検査できる。文字列中の`0000`を無差別に検索する実装や、未知書式への推測は不要。[Oracle ORA-01841](https://docs.oracle.com/en/error-help/db/ora-01841/)は補助資料とし、18cのエラー順・SQLStateは既存の発見記録と追加実測で確定する。
- P1に`char_text`、P2までにBYTE長検査がある。LENGTHBではCHARの空白保持を共有できる。LENGTHBのLOB制限と、ASCIIがDB文字セットの値を返す点は別途契約に含める必要がある。[Oracle 18c LENGTH](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/LENGTH.html)、[ASCII](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/ASCII.html)
- RATIO_TO_REPORTは集合の合計に対する比率であり、P1の除算を再利用する余地がある。ただしSUMの中間精度や式評価回数までP1で解決済みとはいえない。P2のSTDDEVで得た「単純な数式置換でも末尾桁がずれる」という実測を設計上の注意点とする。STDDEVの失敗を、この関数も必ず失敗する証拠とは扱わない。[Oracle 18c RATIO_TO_REPORT](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/RATIO_TO_REPORT.html)

### 次期対応を推奨する2件

| ID | 利用・影響の見込み | 追加負担の見込み | 実装範囲と完了条件 |
|---|---|---|---|
| sequence-in-where | 不正SQLの利用頻度は低くても、誤って受け付けると採番の副作用がある。頻度だけで優先度を下げるべきでない。 | 小～中。既存の擬似列識別・AST・エラー変換を再利用。 | SELECTのWHEREを重複参照の有無と独立に検査する。ネスト、大小文字、引用識別子、NEXTVAL/CURRVALを確認。既存の正常なSELECT/INSERT/UPDATEを壊さず、拒否時に値を消費しないこととOracleのvendor code・SQLStateを比較する。 |
| to-date-year-zero | 入力検証。低～中頻度だが、既存TO_DATEの正確性を小さく改善できる。 | 小。既存の年抽出とエラー経路を共有できる範囲。 | まずYYYY-MM-DD/FXYYYY-MM-DD。バインド、年0000/0001、NULL、無効月日・余剰文字とのエラー優先順位を比較。YY/RR/BCや全書式パーサへの拡張は分ける。 |

この2件は実利用SQLの提出待ちにせず、次のTDD実装対象とすることを推奨した。後続のユーザー指示に基づき、次節のとおり実装した。

### 条件付き候補5件

| ID | 利用の見込み・再評価 | 追加負担の見込み | 着手条件・打ち切り条件 |
|---|---|---|---|
| lengthb-unicode | 日本語のBYTE列長チェック・外部連携で有用。条件付き候補の最優先。既存のCHAR空白保持とバイト長処理を共有できる。 | 小～中 | Oracle AL32UTF8とPG UTF8など対象環境を確認・記録し、まずCHAR/VARCHAR2に限定。日本語、補助文字、末尾空白、空文字、NULL、列・バインド、JDBC NUMBERを比較。NCHAR/LOBや別文字セットへ暗黙に一般化しない。 |
| ascii-unicode | 文字コード処理をする移行SQLでは利用可能性がある。LENGTHBで文字セット契約を確立した後なら追加負担が下がる。 | 小～中 | 対象DB文字セットで先頭文字のバイト表現を検証。単なるUnicodeコードポイント返却に置換しない。国別文字型の変換規則が必要なら別設計に戻す。 |
| instrb-unicode | バイト位置を持つ外部形式で有用。既存INSTRの探索・引数検査は参考にできる。 | 中 | 文字セット契約が先。負の開始位置、文字の途中の開始位置、重複一致、出現回数、空文字をOracle比較。文字位置を最後にバイト数へ変換するだけの実装では不足。 |
| initcap-word-boundary | 元ケースは値が一致しJDBC precisionのみ相違。表示名整形などで使うなら、狭いメタデータ修正で済む可能性がある。 | 小～中。ただし列・Unicode全般なら増える。 | 元のASCIIリテラルだけを特殊処理せず、同じ規則を適用できる文字型・式の範囲を確定。単語境界・Unicodeの大小変換と出力幅を比較。大きなロケール別変換表が必要なら見送り。 |
| ratio-to-report | 帳票の構成比で利用が見込まれる。P1の除算を再利用できるため、一律見送りから設計候補へ変更。 | 中 | まずNUMBER列とPARTITION BY。合計0、全NULL、負数、桁数境界、派生表、JDBC scaleを比較。一般式の二重評価・バインド重複や数値集約の精度で大きな投影書き換えが必要なら見送り。 |

BYTE系では、既存のAL32UTF8を前提とするテスト名だけを環境保証に使わない。接続先の実際の文字セット・適用可能な型を確認し、設定や検証の仕組みも含めて追加コストを見積もる。
INSTRBの位置・出現回数はバイト単位で検証する。[Oracle 18c INSTR](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/INSTR.html)
INITCAPの再評価は元の実測差分と既存メタデータ処理からの見込みであり、全言語で値が一致するという意味ではない。[Oracle 18c INITCAP](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/INITCAP.html)

### 見送りを維持する13件

| ID | 維持する理由 | 再開する条件 |
|---|---|---|
| substrb-ascii | ASCII限定なら小さく作れるが、元のASCIIケースだけでは実用のマルチバイト境界処理を保証できない。LENGTHBより負担が大きい。 | BYTE系契約を確立したうえで、切断位置を含む実SQLがある。ASCII限定機能を設けるなら非ASCII入力を実行時にも確実に拒否できること。 |
| chr-zero | PostgreSQLの文字列型はNULを格納できず、既存textランタイムでは表現できない。 | NULを必要とする具体的用途と、文字列/JDBC表現を変更する費用を許容できる。 |
| lag-ignore-nulls | 時系列では有用であり「使われない」ためではない。NULL除外、offset/default、PARTITION、同順位行の順序、性能を一体で扱う必要がある。 | 実用の分析SQLとデータ量があり、LAG/LEADで共有する設計を作れる。 |
| median | 集計用途はあるが、順序統計・NULL・偶数件の補間・型/精度を扱う共通基盤がまだない。 | NUMBER中心の集計需要があり、PERCENTILE_CONTと共通にOracle中間丸めを検証できる。 |
| soundex | 英語名照合など用途が限られる。P1/P2の再利用による追加コスト低下が小さい。 | 実SQLと照合対象の言語・文字集合が確定する。 |
| nanvl | NUMBERの除算・乗算対応はBINARY_FLOAT/BINARY_DOUBLEのNaN対応を意味しない。 | 特殊浮動小数の型優先順位・バインド・JDBC値を共通設計する需要がある。 |
| dump-number | 診断用途のOracle内部表現再現であり、通常の業務結果を改善する費用対効果が低い。 | 内部バイト表現を検査する具体的な移行・診断要件がある。 |
| to-single-byte | DB文字セット別の変換範囲と出力型を定義する必要がある。汎用Unicode正規化への置換では保証できない。 | 対象文字セット・変換文字集合・実SQLが明確で、変換表を継続保守できる。 |
| to-multi-byte | 上と同じ変換基盤が必要。P1のCHAR空白保持では代替できない。 | TO_SINGLE_BYTEと対で変換契約を保守できる。 |
| unistr | エスケープ解釈だけでなく国別文字型・サロゲート・JDBCメタデータの対応が必要。 | NVARCHAR2を含む国別文字型をまとめて扱う需要がある。 |
| hextoraw | PostgreSQLのバイト変換を使える可能性はあるが、RAWTOHEXとの合成1ケースだけ直すのは不十分。RAW単体のJDBC値・幅・バインドと不正入力の契約が必要。 | RAW列・バインドを使う移行SQLがあり、RAW全体を一緒に設計する。 |
| percentile-cont | 分位点・順序・補間・NULLと中間精度が必要。doubleの組込み集約への単純置換は採用できない。 | MEDIANと共有できる順序統計の需要と、Oracle比較の境界データがある。 |
| keep-dense-rank | 帳票や履歴データでは有用だが、同順位の集合に対する集約・NULL・FIRST/LAST・性能を保存する構造変更が必要。 | 実SQLをもとに分析関数の共通設計として扱える。 |

SUBSTRBはバイト単位であり、LAGのIGNORE NULLSは対象値のNULLを計算から除く契約である。[Oracle 18c SUBSTR](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/SUBSTR.html)、[LAG](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/LAG.html)
CHR(0)の障害はPostgreSQLの文字列表現にある。[PostgreSQL 17 Character Types](https://www.postgresql.org/docs/17/datatype-character.html)
MEDIANの補間とHEXTORAWのRAW結果型も、現在のNUMBER除算や文字列メタデータだけでは全契約を満たさない。[Oracle 18c MEDIAN](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/MEDIAN.html)、[HEXTORAW](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/HEXTORAW.html)

### 次の作業順と今回の検証範囲

推奨順は、(1) WHERE内シーケンス検査、(2) TO_DATE年0、(3) 対象文字セットの確認とLENGTHB、(4) 条件が整った残り4候補。
分析関数やRAWは、実SQLから複数の機能で共有できる設計を作れる場合に再開する。
再評価段階では判断文書のみを変更し、20件の分類が2+5+13で重複・欠落なく一致すること、既存67行のIDと再現SQLが維持されること、LFとgit diff --checkを検査した。以下の実装については新たにMaven/Oracle比較を行い、過去の成功件数を流用しない。


## P3推奨2件の実装（2026-09-06）

P2のa4dc0d4から`fix/oracle-p3-validation`を作成。再評価文書と、この2件の製品コード・回帰テストを同じコミットにまとめる。
条件付き候補5件と見送り13件は未実装のまま。実装対象は`sequence-in-where`と`to-date-year-zero`であり、P3全20件への対応ではない。

### 変更と対応範囲

- WHERE検査は既存`SequenceProjectionRule`の識別処理を再利用し、SQLの構造変更前に実施する。投影にNEXTVALがない場合や重複しない場合も検査する。Oracleのエラーコード2287・SQLState 42000を既存のJDBC変換に追加した。
- `NEXTVAL`/`CURRVAL`という名前だけでは判定せず、P1の列型解決で通常列と判明するものを区別する。この判定は配置検査・投影処理・scalar書き換えで共有する。派生表の引用列、物理列、大小文字混在を確認した。新しい型推論器やSQL再解析は追加していない。
- TO_DATEは既存の数値日付検査を拡張。対象はYYYY-MM-DD/FXYYYY-MM-DDのハイフン区切り入力（可変桁を含む）と、非FXでの8桁数字の年0入力。月の範囲外は1843、日の範囲外は1847を先に返す。年0は1841、非FXの8桁数字で年0の場合は1861になることをOracle比較で確定した。月名、YY/RR、BC、別の区切り・書式全般のエラー順まで対応する主張ではない。
- 年0001、NULL、時刻部分の00は拒否しない。TO_DATEバインドを年0から正常値・NULLへ変更するテストと、エラー後のトランザクション内再実行を追加した。
- WHERE拒否時にシーケンス値を消費せず、正常なSELECT NEXTVAL・INSERT VALUES・UPDATE SETの採番を維持することをローカル統合テストで確認した。

### TDDで確認した差分

1. 元の再現2件と境界17件を先に既存TOMLに追加。修正前の495件中16件が失敗した（`/tmp/orafit-p3-validation-red.log`）。この16件には初期の予想とOracleエラー優先順位の違い、および下記の既存メタデータ差分も含む。元の2件だけの件数と混同しない。
2. ローカル統合テスト2件も修正前に失敗することを確認した（`/tmp/orafit-p3-validation-local-red.log`）。採番を実行前に止めること、年0をエラーにすることを先にテストした。
3. Oracleは`0000-13-01`で1841より1843を優先し、区切りを省いた`00000101`は1861を返した。期待値はこの実測で確定し、製品側の検査順を修正した。許容誤差やrejectへの変更は行っていない。
4. `q."NEXTVAL"`という通常列を誤拒否する失敗をOracle比較で検出し（`/tmp/orafit-p3-validation-oracle-1.log`）、既存の列型解決を共有して修正。物理列のローカル統合テストも追加した。

### 別途発見した未対応のメタデータ差分

探索SQL `SELECT "NEXTVAL" FROM (SELECT 1 AS "NEXTVAL" FROM dual) q WHERE "NEXTVAL" = 1` は、値が一致する一方、JDBC scaleがOracle -127 / Orafit 0となった（`/tmp/orafit-p3-validation-red.log`）。これは通常列の投影を擬似列名のメタデータとして扱う既存の問題で、今回の2件には含めない。
通常列のWHEREが誤拒否されないことを検証する回帰ケースは、投影を`1 AS value`としてWHEREの識別を検証する。この変更で上記メタデータ問題が修正されたとは主張せず、元SQLと実測をここに保存する。

### P3推奨2件の最終検証

- `./mvnw verify`: **672件、Failures 0、Errors 0、Skipped 0**。書式、runtimeパッケージ、JAR内容検証も成功。ログ: `/tmp/orafit-p3-validation-verify-final.log`。
- `./.github/workflows/ci.sh`: **Oracle 18c XE 18.4.0.0.0 対 embedded PostgreSQL 17.10、501件、Failures 0、Errors 0、Skipped 0、終了コード0**。ログ: `/tmp/orafit-p3-validation-oracle-final.log`。
- 既存TOMLに追加したのは**25件、すべてsame**（元の再現2件＋境界23件）。別途ローカル統合テスト3件。既存P0/P1/P2の期待値・reject契約・検証方法は変更していない。
- 元の67件は重複・欠落なし。**46件対応済み、21件未対応**。未対応の内訳はP2見送り3件、元P3の条件付き候補5件・見送り13件。探索で見つけた通常NEXTVAL列の投影メタデータ問題は67件の外であり、上記対応済み件数に含めない。

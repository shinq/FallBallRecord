﻿## 概要
https://github.com/shinq/FallBallRecord

PC版フォールガイズ(Fall Guys)向けのフォールボール戦績管理とモチベ向上を目指したアプリです。

主にカスタムフォールボールの試合状況を記録して、勝率などから各種実績達成を可視化します。

汎用版として FallGuysRecord も同様の機能拡張予定です。他者の情報をマッチを跨いで管理できなくなったため、自身の情報に絞って機能拡張した形になります。
FallGuysStats とあまり変わらない趣旨になってしまうので、差別化のため「実績」を定義して達成表示するようにしています。

## 初期設定
* Java 実行環境をインストールする必要があります。(https://java.com/)
* ダウンロード：上部の緑の「Code」ボタンから Download ZIP を選んでダウンロードし、展開してください。

## バージョンアップについて
* 更新版に差し替える場合、最新版 zip を展開後、もとの環境から state.dat / stats.tsv / setting.ini ファイルだけ新しく展開したフォルダにコピーしてください。

## 使い方
* FallBallRecord.jar をダブルクリックします。

## 機能概要
* 試合ごとの結果が記録されていきます。
* 現在のマッチの開始終了時刻と接続先サーバ情報が表示されます。
* 試合数や勝率などに応じて実績の達成判定がされます。
 * 実績ごとに付与ポイントが設定されており、合計獲得ポイントに応じて称号が得られます。
* デイリーチャレンジが３つ設定されています。付与ポイントを増やすチャンス!!w

* 過去の試合結果も保持します。
* カスタムフォールボールのみ、野良フォールボールのみ、その他絞り込み表示ができます。
  (実績は実績ごとに条件が設定されているので絞り込みは影響しません)
* 試合数絞り込みは最近のn試合での勝率表示となります。現在起動中フォールガイズ分の戦績は今回の戦績として常に表示されます。

* 「ラウンド一覧」で選択したラウンドに対して以下調整ができます。
 * 「ラウンド不参加切り替え」は、自分が審判をやったかを設定します。不参加の場合は☓がついてそのラウンドは集計対象外となります。
 * 「審判有無切り替え」は、奇数での試合時に審判が居たかどうかを設定できます。デフォルトで20人以下の場合は審判が居たものとして(-1)の補正がかかっているのでこれを実際の状況に応じて切り替えできます。

* settings.ini を書き換えてから起動することで、ランキング部分のフォントサイズ、表示言語を変更できます。
 * 現在指定可能な LANGUAGE は空欄(英語)または ja です。

## 制限、注意など
* マッチの開始時刻はマッチングで数値が出た時刻-最終ラウンドが終了した瞬間の時刻です。１マッチにかかった時間総数とは少し異なります。次のマッチ開始時刻との差が実際にかかった時間と言えます。
* 今は実績判定＆付与ポイント計算を毎回行うので無駄に重いです。ツールの目的からすると、fallguys終了後に一度起動すればいいものではありますが。
* 一回前のセッション分を読み込む機能がまだです。

## 履歴
2024/5/8 ver0.4.0
* 最新版ログ改変に対応

2024/4/15 V0.3.9
* レート計算サーバに戦績自動送信しての「レート戦」に対応。ただしサーバ側の設定と、参加者の特定が必要なので任意に使えるわけではありません(隠し機能的位置づけ)
 * settings.ini の PLAYER_NAME に自分の名前を書き、く。現状はフォールボール協会で「レート戦」を開催しない限り利用価値はないです…。
* PING 取得で起動が重くなっていたのでバックグラウンド化
* 日付検出が動作しなくなっていたのを修正


2023/5/10 v0.3.8
* SS4 対応と高速化

2023/1/23 v0.3.7
* 切断を負けとしてカウントするよう修正。勝利人数など不明になるので表示は総人数とその時点の点数。

2023/1/21 v0.3.6
* サブショーとカスタムを区別できるよう修正。絞り込みも追加。

2023/1/16 v0.3.5
* weekly 達成計測バグ修正。

2023/1/14 v0.3.4
* weekly challenge を追加。内容は固定で毎週。

2023/1/14 v0.3.3
* ラウンドの日付を誤認する問題の改善。誤認していた場合、デイリー達成判定がバグる。
* ただ、前バージョンで間違った日付で記録されたラウンドと別マッチと判定されて重複が起こるので、一時的に、別日の同一時刻マッチがあったら古い方を消す処理を入れている。正常なラウンドが消されうるのでしばらくしたら外す予定。
* こういったことの関係上まだ統計情報が破損する可能性があるので念のため stats_prev.tsv を作るように。
* ping は一度そのマッチの ping 取得したらそちらを優先して再度は行わないようにする。
* アプリ起動直後の表示をできるだけ最新状態にする修正。

2023/1/12 v0.3
* 実績、やラウンドフィルタ調整。

2023/1/4 v0.2 first release
* 実績、デイリーチャレンジの調整をして、使い始められる状態に。

2023/1/1 v0.1 draft
* 実績などは仮の状態。戦績保存フォーマットもまだ非互換の変更の可能性があります。

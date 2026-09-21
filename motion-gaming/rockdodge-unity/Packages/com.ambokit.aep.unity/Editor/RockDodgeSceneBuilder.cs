#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.UI;
using System;
using AmboKit.AEP.RockDodge;
using AmboKit.AEP.Unity.Rendering;

namespace AmboKit.AEP.Editor
{
    public static class RockDodgeSceneBuilder
    {
        [MenuItem("Ambo/AEP/Create Rock Dodge Scene")]
        public static void Create()
        {
            var scene=EditorSceneManager.NewScene(NewSceneSetup.EmptyScene,NewSceneMode.Single);
            var cam=new GameObject("Main Camera").AddComponent<Camera>();cam.clearFlags=CameraClearFlags.SolidColor;cam.backgroundColor=Color.black;cam.orthographic=true;
            var canvasGo=new GameObject("RockDodgeCanvas",typeof(Canvas),typeof(CanvasScaler),typeof(GraphicRaycaster));var canvas=canvasGo.GetComponent<Canvas>();canvas.renderMode=RenderMode.ScreenSpaceOverlay;canvas.pixelPerfect=true;var scaler=canvasGo.GetComponent<CanvasScaler>();scaler.uiScaleMode=CanvasScaler.ScaleMode.ScaleWithScreenSize;scaler.referenceResolution=new Vector2(1920,1080);scaler.matchWidthOrHeight=.5f;
            var bg=Child<RawImage>(canvasGo.transform,"WorldBackground");Stretch(bg.rectTransform);var tex=FindBackground();bg.texture=tex;bg.color=Color.white;
            var overlay=Child<RockDodgeOverlayGraphic>(canvasGo.transform,"GameplayOverlay");Stretch(overlay.rectTransform);overlay.raycastTarget=false;
            var live=Child<RawImage>(canvasGo.transform,"LivePerson");live.rectTransform.sizeDelta=new Vector2(330,590);live.color=Color.white;live.gameObject.SetActive(false);
            var title=Text(canvasGo.transform,"Title","ROCK DODGE",54,TextAnchor.UpperCenter,new Vector2(.5f,1),new Vector2(0,-22),new Vector2(900,90));
            var hud=Text(canvasGo.transform,"HUD","",40,TextAnchor.UpperCenter,new Vector2(.5f,1),new Vector2(0,-88),new Vector2(1200,70));
            var status=Text(canvasGo.transform,"Status","Starting AEPâ€¦",30,TextAnchor.LowerCenter,new Vector2(.5f,0),new Vector2(0,35),new Vector2(1400,70));
            var qr=Child<RawImage>(canvasGo.transform,"JoinQr");qr.rectTransform.anchorMin=qr.rectTransform.anchorMax=new Vector2(.5f,.5f);qr.rectTransform.anchoredPosition=new Vector2(0,40);qr.rectTransform.sizeDelta=new Vector2(420,420);qr.color=Color.white;qr.gameObject.SetActive(false);
            var join=Text(canvasGo.transform,"Join","",22,TextAnchor.MiddleCenter,new Vector2(.5f,.5f),new Vector2(0,-245),new Vector2(1500,150));join.horizontalOverflow=HorizontalWrapMode.Wrap;
            var mode=Text(canvasGo.transform,"Mode","MODE: POSE   â† / â†’ choose before play",28,TextAnchor.UpperCenter,new Vector2(.5f,1),new Vector2(0,-145),new Vector2(1000,65));
            var restartGo=new GameObject("RestartButton",typeof(RectTransform),typeof(Image),typeof(Button));restartGo.transform.SetParent(canvasGo.transform,false);var rr=(RectTransform)restartGo.transform;rr.anchorMin=rr.anchorMax=new Vector2(.5f,.18f);rr.sizeDelta=new Vector2(360,85);restartGo.GetComponent<Image>().color=new Color(.18f,.08f,.04f,.9f);var restart=restartGo.GetComponent<Button>();var rt=Text(restartGo.transform,"Label","RESTART GAME",30,TextAnchor.MiddleCenter,new Vector2(.5f,.5f),Vector2.zero,new Vector2(340,70));restartGo.SetActive(false);
            var ctlGo=new GameObject("RockDodgeExperience");var ctl=ctlGo.AddComponent<RockDodgeUnityController>();ctl.Overlay=overlay;ctl.LiveImage=live;ctl.JoinQr=qr;ctl.StatusText=status;ctl.JoinText=join;ctl.HudText=hud;ctl.TitleText=title;ctl.ModeText=mode;ctl.RestartButton=restart;ctl.EmbeddedGateway=true;
            var es=new GameObject("EventSystem",typeof(UnityEngine.EventSystems.EventSystem)); var inputUi=Type.GetType("UnityEngine.InputSystem.UI.InputSystemUIInputModule, Unity.InputSystem"); if(inputUi!=null) es.AddComponent(inputUi); else Debug.LogWarning("Unity Input System UI module not found; Restart button pointer/remote input may require an EventSystem input module.");
            Selection.activeGameObject=ctlGo;EditorSceneManager.MarkSceneDirty(scene);EditorSceneManager.SaveScene(scene,"Assets/RockDodgeUnity.unity");Debug.Log("AEP Rock Dodge scene created at Assets/RockDodgeUnity.unity");
        }
        static Texture2D FindBackground(){var ids=AssetDatabase.FindAssets("rockdodge_volcano_world t:Texture2D");if(ids.Length==0){Debug.LogError("Rock Dodge runtime background asset is missing from the package.");return Texture2D.whiteTexture;}var path=AssetDatabase.GUIDToAssetPath(ids[0]);EnsureTextureQuality(path);return AssetDatabase.LoadAssetAtPath<Texture2D>(path);} 
        static void EnsureTextureQuality(string path){var imp=AssetImporter.GetAtPath(path) as TextureImporter;if(imp==null)return;bool dirty=false;if(imp.maxTextureSize<4096){imp.maxTextureSize=4096;dirty=true;}if(imp.textureCompression!=TextureImporterCompression.Uncompressed){imp.textureCompression=TextureImporterCompression.Uncompressed;dirty=true;}if(imp.filterMode!=FilterMode.Bilinear){imp.filterMode=FilterMode.Bilinear;dirty=true;}if(imp.mipmapEnabled){imp.mipmapEnabled=false;dirty=true;}if(imp.wrapMode!=TextureWrapMode.Clamp){imp.wrapMode=TextureWrapMode.Clamp;dirty=true;}if(dirty)imp.SaveAndReimport();}
        static T Child<T>(Transform p,string name) where T:Component{var g=new GameObject(name,typeof(RectTransform),typeof(T));g.transform.SetParent(p,false);return g.GetComponent<T>();}
        static void Stretch(RectTransform r){r.anchorMin=Vector2.zero;r.anchorMax=Vector2.one;r.offsetMin=r.offsetMax=Vector2.zero;}
        static Text Text(Transform p,string name,string text,int size,TextAnchor align,Vector2 anchor,Vector2 pos,Vector2 dims){var t=Child<Text>(p,name);t.text=text;t.font=Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");t.fontSize=size;t.fontStyle=FontStyle.Bold;t.alignment=align;t.color=Color.white;t.supportRichText=true;t.rectTransform.anchorMin=t.rectTransform.anchorMax=anchor;t.rectTransform.anchoredPosition=pos;t.rectTransform.sizeDelta=dims;t.resizeTextForBestFit=false;return t;}
    }
}
#endif

